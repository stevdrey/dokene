CREATE TABLE dokene.workspace_provisioning_records (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    identity_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_provisioning_tenant
        FOREIGN KEY (tenant_id) REFERENCES dokene.tenants (id) ON DELETE RESTRICT,
    CONSTRAINT uq_provisioning_identity_key
        UNIQUE (identity_id, idempotency_key),
    CONSTRAINT ck_provisioning_idempotency_key_not_blank CHECK (
        idempotency_key ~ U&'[^[:space:]\001C-\001F\0085\00A0\1680\2000-\200A\2007\2028\2029\202F\205F\3000]'
    ),
    CONSTRAINT ck_provisioning_display_name_not_blank CHECK (
        display_name ~ U&'[^[:space:]\001C-\001F\0085\00A0\1680\2000-\200A\2007\2028\2029\202F\205F\3000]'
    )
);

REVOKE ALL ON TABLE dokene.workspace_provisioning_records FROM PUBLIC;
GRANT SELECT, INSERT ON TABLE dokene.workspace_provisioning_records TO dokene_runtime;
