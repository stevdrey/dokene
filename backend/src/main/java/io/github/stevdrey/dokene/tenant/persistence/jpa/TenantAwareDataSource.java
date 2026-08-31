package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * DataSource decorator that propagates the active tenant context to PostgreSQL session
 * configuration ({@code dokene.current_tenant_id}) for Row-Level Security policy enforcement.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    private final TenantContextProvider tenantContextProvider;

    public TenantAwareDataSource(DataSource targetDataSource, TenantContextProvider tenantContextProvider) {
        super(targetDataSource);
        this.tenantContextProvider = Objects.requireNonNull(tenantContextProvider, "Tenant context provider is required");
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection targetConnection = obtainTargetDataSource().getConnection();
        return wrapConnection(targetConnection);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Connection targetConnection = obtainTargetDataSource().getConnection(username, password);
        return wrapConnection(targetConnection);
    }

    private Connection wrapConnection(Connection targetConnection) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new TenantAwareConnectionInvocationHandler(targetConnection, tenantContextProvider)
        );
    }

    private static final class TenantAwareConnectionInvocationHandler implements InvocationHandler {

        private static final String SETTING_NAME = "dokene.current_tenant_id";

        private final Connection target;
        private final TenantContextProvider tenantContextProvider;
        private UUID appliedTransactionTenantId;
        private UUID appliedSessionTenantId;
        private boolean transactionContextApplied;

        TenantAwareConnectionInvocationHandler(Connection target, TenantContextProvider tenantContextProvider) {
            this.target = target;
            this.tenantContextProvider = tenantContextProvider;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            switch (methodName) {
                case "unwrap" -> {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(proxy)) {
                        return proxy;
                    }
                    if (iface.isInstance(target)) {
                        return target;
                    }
                    return target.unwrap(iface);
                }
                case "isWrapperFor" -> {
                    Class<?> iface = (Class<?>) args[0];
                    return iface.isInstance(proxy) || iface.isInstance(target) || target.isWrapperFor(iface);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAwareConnectionProxy[" + target + "]";
                }
                case "close" -> {
                    try {
                        resetSessionSettingIfNecessary();
                    } finally {
                        target.close();
                    }
                    return null;
                }
                case "setAutoCommit" -> {
                    boolean autoCommit = (boolean) args[0];
                    target.setAutoCommit(autoCommit);
                    invalidateTransactionContext();
                    return null;
                }
                case "commit", "rollback" -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    } finally {
                        invalidateTransactionContext();
                    }
                }
                case "createStatement" -> {
                    ensureTenantContextApplied();
                    try {
                        Object result = method.invoke(target, args);
                        if (result instanceof Statement statement) {
                            return wrapStatement(statement, proxy, null);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
                case "prepareStatement", "prepareCall" -> {
                    SqlTransactionControlDetector.reject(args);
                    ensureTenantContextApplied();
                    try {
                        Object result = method.invoke(target, args);
                        String sql = SqlTransactionControlDetector.sqlArgument(args);
                        if (result instanceof Statement statement) {
                            return wrapStatement(statement, proxy, sql);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
                case "getMetaData" -> {
                    try {
                        Object result = method.invoke(target, args);
                        if (result instanceof DatabaseMetaData metadata) {
                            return wrapDatabaseMetaData(metadata, proxy);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
                default -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            }
        }

        void invalidateTransactionContext() {
            transactionContextApplied = false;
            appliedTransactionTenantId = null;
        }

        private Statement wrapStatement(Statement targetStatement, Object connectionProxy, String sql) {
            Class<?>[] interfaces;
            if (targetStatement instanceof CallableStatement) {
                interfaces = new Class<?>[]{CallableStatement.class};
            } else if (targetStatement instanceof PreparedStatement) {
                interfaces = new Class<?>[]{PreparedStatement.class};
            } else {
                interfaces = new Class<?>[]{Statement.class};
            }
            return (Statement) Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    interfaces,
                    new TenantAwareStatementInvocationHandler(targetStatement, this, connectionProxy, sql)
            );
        }

        private DatabaseMetaData wrapDatabaseMetaData(DatabaseMetaData targetMetadata, Object connectionProxy) {
            return (DatabaseMetaData) Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(),
                    new Class<?>[]{DatabaseMetaData.class},
                    new TenantAwareDatabaseMetaDataInvocationHandler(targetMetadata, connectionProxy)
            );
        }

        void ensureTenantContextApplied() throws SQLException {
            boolean inTransaction = !target.getAutoCommit();
            applyTenantContext(inTransaction);
        }

        private void applyTenantContext(boolean inTransaction) throws SQLException {
            Optional<TenantId> activeTenant = tenantContextProvider.currentTenantId();
            UUID targetTenantId = activeTenant.map(TenantId::value).orElse(null);

            if (inTransaction) {
                if (transactionContextApplied && Objects.equals(appliedTransactionTenantId, targetTenantId)) {
                    return;
                }

                if (targetTenantId != null) {
                    try (PreparedStatement statement = target.prepareStatement("SELECT set_config(?, ?, true)")) {
                        statement.setString(1, SETTING_NAME);
                        statement.setString(2, targetTenantId.toString());
                        statement.execute();
                    }
                    appliedTransactionTenantId = targetTenantId;
                } else {
                    try (PreparedStatement statement = target.prepareStatement("SELECT set_config(?, '', true)")) {
                        statement.setString(1, SETTING_NAME);
                        statement.execute();
                    }
                    appliedTransactionTenantId = null;
                }
                transactionContextApplied = true;
            } else {
                if (Objects.equals(appliedSessionTenantId, targetTenantId)) {
                    return;
                }

                if (targetTenantId != null) {
                    try (PreparedStatement statement = target.prepareStatement("SELECT set_config(?, ?, false)")) {
                        statement.setString(1, SETTING_NAME);
                        statement.setString(2, targetTenantId.toString());
                        statement.execute();
                    }
                    appliedSessionTenantId = targetTenantId;
                } else {
                    resetSessionSettingIfNecessary();
                }
            }
        }

        private void resetSessionSettingIfNecessary() throws SQLException {
            if (appliedSessionTenantId != null) {
                try {
                    if (!target.isClosed()) {
                        if (!target.getAutoCommit()) {
                            try {
                                target.rollback();
                            } catch (SQLException ignored) {
                            }
                            try {
                                target.setAutoCommit(true);
                            } catch (SQLException ignored) {
                            }
                        }
                        try (Statement statement = target.createStatement()) {
                            statement.execute("RESET " + SETTING_NAME);
                        }
                    }
                    appliedSessionTenantId = null;
                } catch (SQLException exception) {
                    try {
                        target.abort(Runnable::run);
                    } catch (Throwable ignored) {
                    }
                    throw exception;
                }
            }
        }
    }

    private static final class TenantAwareDatabaseMetaDataInvocationHandler implements InvocationHandler {

        private final DatabaseMetaData target;
        private final Object connectionProxy;

        TenantAwareDatabaseMetaDataInvocationHandler(DatabaseMetaData target, Object connectionProxy) {
            this.target = target;
            this.connectionProxy = connectionProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            switch (methodName) {
                case "getConnection" -> {
                    return connectionProxy;
                }
                case "unwrap" -> {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(proxy)) {
                        return proxy;
                    }
                    if (iface.isInstance(target)) {
                        return target;
                    }
                    return target.unwrap(iface);
                }
                case "isWrapperFor" -> {
                    Class<?> iface = (Class<?>) args[0];
                    return iface.isInstance(proxy) || iface.isInstance(target) || target.isWrapperFor(iface);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAwareDatabaseMetaDataProxy[" + target + "]";
                }
                default -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            }
        }
    }

    private static final class TenantAwareStatementInvocationHandler implements InvocationHandler {

        private final Statement target;
        private final TenantAwareConnectionInvocationHandler connectionHandler;
        private final Object connectionProxy;
        private final String preparedSql;

        TenantAwareStatementInvocationHandler(
                Statement target,
                TenantAwareConnectionInvocationHandler connectionHandler,
                Object connectionProxy,
                String preparedSql
        ) {
            this.target = target;
            this.connectionHandler = connectionHandler;
            this.connectionProxy = connectionProxy;
            this.preparedSql = preparedSql;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if (methodName.startsWith("execute")) {
                SqlTransactionControlDetector.reject(sqlForExecution(args));
                connectionHandler.ensureTenantContextApplied();
            }

            switch (methodName) {
                case "addBatch" -> {
                    SqlTransactionControlDetector.reject(args);
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
                case "getConnection" -> {
                    return connectionProxy;
                }
                case "unwrap" -> {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(proxy)) {
                        return proxy;
                    }
                    if (iface.isInstance(target)) {
                        return target;
                    }
                    return target.unwrap(iface);
                }
                case "isWrapperFor" -> {
                    Class<?> iface = (Class<?>) args[0];
                    return iface.isInstance(proxy) || iface.isInstance(target) || target.isWrapperFor(iface);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAwareStatementProxy[" + target + "]";
                }
                default -> {
                    try {
                        Object result = method.invoke(target, args);
                        if (result instanceof ResultSet resultSet) {
                            return wrapResultSet(resultSet, proxy);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            }
        }

        private String sqlForExecution(Object[] args) {
            String directSql = SqlTransactionControlDetector.sqlArgument(args);
            return directSql != null ? directSql : preparedSql;
        }

        private ResultSet wrapResultSet(ResultSet targetResultSet, Object statementProxy) {
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(),
                    new Class<?>[]{ResultSet.class},
                    new TenantAwareResultSetInvocationHandler(targetResultSet, statementProxy, connectionHandler)
            );
        }
    }

    private static final class TenantAwareResultSetInvocationHandler implements InvocationHandler {

        private final ResultSet target;
        private final Object statementProxy;
        private final TenantAwareConnectionInvocationHandler connectionHandler;

        TenantAwareResultSetInvocationHandler(
                ResultSet target,
                Object statementProxy,
                TenantAwareConnectionInvocationHandler connectionHandler
        ) {
            this.target = target;
            this.statementProxy = statementProxy;
            this.connectionHandler = connectionHandler;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if (performsDatabaseWork(methodName)) {
                connectionHandler.ensureTenantContextApplied();
            }

            switch (methodName) {
                case "getStatement" -> {
                    return statementProxy;
                }
                case "unwrap" -> {
                    Class<?> iface = (Class<?>) args[0];
                    if (iface.isInstance(proxy)) {
                        return proxy;
                    }
                    if (iface.isInstance(target)) {
                        return target;
                    }
                    return target.unwrap(iface);
                }
                case "isWrapperFor" -> {
                    Class<?> iface = (Class<?>) args[0];
                    return iface.isInstance(proxy) || iface.isInstance(target) || target.isWrapperFor(iface);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "TenantAwareResultSetProxy[" + target + "]";
                }
                default -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                }
            }
        }

        private static boolean performsDatabaseWork(String methodName) {
            return switch (methodName) {
                case "updateRow", "deleteRow", "insertRow", "refreshRow" -> true;
                default -> false;
            };
        }
    }

    private static final class SqlTransactionControlDetector {

        private SqlTransactionControlDetector() {
        }

        static void reject(Object[] args) throws SQLException {
            reject(sqlArgument(args));
        }

        static void reject(String sql) throws SQLException {
            if (sql != null && containsTransactionControlStatement(sql)) {
                throw new SQLException("Raw SQL transaction control is not supported; use Connection transaction methods instead");
            }
        }

        static String sqlArgument(Object[] args) {
            return args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
        }

        static boolean containsTransactionControlStatement(String sql) {
            int index = 0;
            while (index < sql.length()) {
                index = skipIgnorable(sql, index);
                if (index >= sql.length()) {
                    return false;
                }

                int keywordEnd = identifierEnd(sql, index);
                if (keywordEnd > index) {
                    String keyword = sql.substring(index, keywordEnd).toUpperCase(Locale.ROOT);
                    if (isTransactionControlKeyword(sql, keyword, keywordEnd)) {
                        return true;
                    }
                }

                index = nextStatementDelimiter(sql, keywordEnd > index ? keywordEnd : index);
                if (index < sql.length()) {
                    index++;
                }
            }
            return false;
        }

        private static boolean isTransactionControlKeyword(String sql, String keyword, int keywordEnd) {
            return switch (keyword) {
                case "BEGIN", "START", "COMMIT", "END", "ROLLBACK", "ABORT", "SAVEPOINT", "RELEASE" -> true;
                case "PREPARE" -> "TRANSACTION".equals(nextKeyword(sql, keywordEnd));
                default -> false;
            };
        }

        private static String nextKeyword(String sql, int index) {
            int start = skipIgnorable(sql, index);
            int end = identifierEnd(sql, start);
            return end > start ? sql.substring(start, end).toUpperCase(Locale.ROOT) : "";
        }

        private static int skipIgnorable(String sql, int index) {
            int current = index;
            boolean skipped;
            do {
                skipped = false;
                while (current < sql.length() && Character.isWhitespace(sql.charAt(current))) {
                    current++;
                    skipped = true;
                }
                if (startsWith(sql, current, "--")) {
                    current = skipLineComment(sql, current + 2);
                    skipped = true;
                } else if (startsWith(sql, current, "/*")) {
                    current = skipBlockComment(sql, current + 2);
                    skipped = true;
                }
            } while (skipped);
            return current;
        }

        private static int nextStatementDelimiter(String sql, int index) {
            int current = index;
            while (current < sql.length()) {
                char character = sql.charAt(current);
                if (character == '\'') {
                    current = skipSingleQuotedLiteral(sql, current + 1);
                } else if (character == '"') {
                    current = skipDoubleQuotedIdentifier(sql, current + 1);
                } else if (startsWith(sql, current, "--")) {
                    current = skipLineComment(sql, current + 2);
                } else if (startsWith(sql, current, "/*")) {
                    current = skipBlockComment(sql, current + 2);
                } else if (character == '$') {
                    int dollarQuotedEnd = skipDollarQuotedLiteral(sql, current);
                    current = dollarQuotedEnd > current ? dollarQuotedEnd : current + 1;
                } else if (character == ';') {
                    return current;
                } else {
                    current++;
                }
            }
            return current;
        }

        private static int skipSingleQuotedLiteral(String sql, int index) {
            int current = index;
            while (current < sql.length()) {
                if (sql.charAt(current) == '\'') {
                    if (current + 1 < sql.length() && sql.charAt(current + 1) == '\'') {
                        current += 2;
                    } else {
                        return current + 1;
                    }
                } else {
                    current++;
                }
            }
            return current;
        }

        private static int skipDoubleQuotedIdentifier(String sql, int index) {
            int current = index;
            while (current < sql.length()) {
                if (sql.charAt(current) == '"') {
                    if (current + 1 < sql.length() && sql.charAt(current + 1) == '"') {
                        current += 2;
                    } else {
                        return current + 1;
                    }
                } else {
                    current++;
                }
            }
            return current;
        }

        private static int skipDollarQuotedLiteral(String sql, int index) {
            int delimiterEnd = index + 1;
            while (delimiterEnd < sql.length() && isIdentifierPart(sql.charAt(delimiterEnd))) {
                delimiterEnd++;
            }
            if (delimiterEnd >= sql.length() || sql.charAt(delimiterEnd) != '$') {
                return index;
            }
            String delimiter = sql.substring(index, delimiterEnd + 1);
            int contentEnd = sql.indexOf(delimiter, delimiterEnd + 1);
            return contentEnd < 0 ? sql.length() : contentEnd + delimiter.length();
        }

        private static int skipLineComment(String sql, int index) {
            int newline = sql.indexOf('\n', index);
            return newline < 0 ? sql.length() : newline + 1;
        }

        private static int skipBlockComment(String sql, int index) {
            int current = index;
            int depth = 1;
            while (current < sql.length() && depth > 0) {
                if (startsWith(sql, current, "/*")) {
                    depth++;
                    current += 2;
                } else if (startsWith(sql, current, "*/")) {
                    depth--;
                    current += 2;
                } else {
                    current++;
                }
            }
            return current;
        }

        private static int identifierEnd(String sql, int index) {
            int current = index;
            while (current < sql.length() && isIdentifierPart(sql.charAt(current))) {
                current++;
            }
            return current;
        }

        private static boolean isIdentifierPart(char character) {
            return Character.isLetterOrDigit(character) || character == '_';
        }

        private static boolean startsWith(String value, int index, String prefix) {
            return index >= 0 && index + prefix.length() <= value.length() && value.startsWith(prefix, index);
        }
    }
}
