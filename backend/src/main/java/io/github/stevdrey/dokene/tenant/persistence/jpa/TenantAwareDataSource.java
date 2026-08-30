package io.github.stevdrey.dokene.tenant.persistence.jpa;

import io.github.stevdrey.dokene.tenant.application.TenantContextProvider;
import io.github.stevdrey.dokene.tenant.domain.TenantId;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
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
                    transactionContextApplied = false;
                    appliedTransactionTenantId = null;
                    return null;
                }
                case "commit", "rollback" -> {
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    } finally {
                        transactionContextApplied = false;
                        appliedTransactionTenantId = null;
                    }
                }
                case "createStatement", "prepareStatement", "prepareCall" -> {
                    ensureTenantContextApplied();
                    try {
                        Object result = method.invoke(target, args);
                        if (result instanceof Statement statement) {
                            return wrapStatement(statement, proxy);
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

        private Statement wrapStatement(Statement targetStatement, Object connectionProxy) {
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
                    new TenantAwareStatementInvocationHandler(targetStatement, this, connectionProxy)
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

    private static final class TenantAwareStatementInvocationHandler implements InvocationHandler {

        private final Statement target;
        private final TenantAwareConnectionInvocationHandler connectionHandler;
        private final Object connectionProxy;

        TenantAwareStatementInvocationHandler(
                Statement target,
                TenantAwareConnectionInvocationHandler connectionHandler,
                Object connectionProxy
        ) {
            this.target = target;
            this.connectionHandler = connectionHandler;
            this.connectionProxy = connectionProxy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();

            if (methodName.startsWith("execute")) {
                connectionHandler.ensureTenantContextApplied();
            }

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
                    return "TenantAwareStatementProxy[" + target + "]";
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
}
