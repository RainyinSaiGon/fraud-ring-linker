-- Merge proposals: a new account linked strongly to two existing clusters, but
-- streaming joins only one. The other is proposed here for an analyst to accept
-- or reject. Nothing is merged until someone accepts.
CREATE TABLE merge_proposals (
    proposal_id        UUID                     NOT NULL,
    status             VARCHAR(16)              NOT NULL,
    trigger_account_id VARCHAR(128)             NOT NULL,
    -- Cluster IDs when proposed. They can be renumbered by a rebuild, so the
    -- accounts on each side are stored too (merge_proposal_accounts).
    cluster_a          VARCHAR(32)              NOT NULL,
    cluster_b          VARCHAR(32)              NOT NULL,
    score              DOUBLE PRECISION         NOT NULL,
    rule               VARCHAR(64)              NOT NULL,
    proposed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by         VARCHAR(128),
    note               VARCHAR(2000),
    decided_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_merge_proposals PRIMARY KEY (proposal_id),
    CONSTRAINT ck_merge_proposals_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_merge_proposals_decided CHECK (
        (status = 'OPEN' AND decided_by IS NULL AND decided_at IS NULL)
        OR (status <> 'OPEN' AND decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE INDEX ix_merge_proposals_status ON merge_proposals (status, proposed_at);

CREATE TABLE merge_proposal_accounts (
    proposal_id UUID         NOT NULL,
    side        CHAR(1)      NOT NULL,
    account_id  VARCHAR(128) NOT NULL,
    CONSTRAINT pk_merge_proposal_accounts PRIMARY KEY (proposal_id, account_id),
    CONSTRAINT ck_merge_proposal_accounts_side CHECK (side IN ('A', 'B')),
    CONSTRAINT fk_merge_proposal_accounts_proposal FOREIGN KEY (proposal_id) REFERENCES merge_proposals (proposal_id)
);
