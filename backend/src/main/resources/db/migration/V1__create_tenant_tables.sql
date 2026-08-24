CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_tenants_display_name_not_blank CHECK (
        display_name ~ U&'[^[:space:]\001C-\001F\00A0\1680\2000-\200A\2007\2028\2029\202F\205F\3000]'
    ),
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'ARCHIVED')),
    CONSTRAINT ck_tenants_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);

CREATE TABLE tenant_memberships (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    identity_id UUID NOT NULL,
    role VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_tenant_memberships_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_tenant_memberships_tenant_identity UNIQUE (tenant_id, identity_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'VIEWER')),
    CONSTRAINT ck_tenant_memberships_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT ck_tenant_memberships_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);
