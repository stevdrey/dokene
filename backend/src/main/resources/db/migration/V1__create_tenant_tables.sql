CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_tenants_display_name_not_blank CHECK (char_length(btrim(display_name)) BETWEEN 1 AND 160),
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
    CONSTRAINT fk_tenant_memberships_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_tenant_memberships_tenant_identity UNIQUE (tenant_id, identity_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'OPERATOR', 'VIEWER')),
    CONSTRAINT ck_tenant_memberships_status CHECK (status IN ('INVITED', 'ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT ck_tenant_memberships_updated_at_not_before_created_at CHECK (updated_at >= created_at)
);
