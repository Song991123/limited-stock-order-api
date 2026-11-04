CREATE TABLE buyers (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username varchar(64) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL
);
CREATE TABLE products (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name varchar(120) NOT NULL
);
CREATE TABLE inventories (
    product_id bigint PRIMARY KEY REFERENCES products(id),
    initial_quantity integer NOT NULL CHECK (initial_quantity >= 0),
    available_quantity integer NOT NULL,
    CHECK (available_quantity >= 0 AND available_quantity <= initial_quantity)
);
CREATE TABLE order_requests (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    buyer_id bigint NOT NULL REFERENCES buyers(id),
    request_key varchar(128) NOT NULL,
    product_id bigint NOT NULL REFERENCES products(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    outcome varchar(20) CHECK (outcome IN ('ACCEPTED', 'OUT_OF_STOCK')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (buyer_id, request_key)
);
CREATE TABLE orders (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id bigint NOT NULL UNIQUE REFERENCES order_requests(id),
    buyer_id bigint NOT NULL REFERENCES buyers(id),
    product_id bigint NOT NULL REFERENCES products(id),
    quantity integer NOT NULL CHECK (quantity > 0),
    status varchar(16) NOT NULL CHECK (status IN ('ACCEPTED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cancelled_at timestamptz,
    CHECK ((status = 'ACCEPTED' AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL))
);
CREATE INDEX orders_buyer_page ON orders(buyer_id, id DESC);
CREATE INDEX orders_product_status ON orders(product_id, status);
CREATE TABLE inventory_movements (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    product_id bigint NOT NULL REFERENCES products(id),
    kind varchar(10) NOT NULL CHECK (kind IN ('DEBIT', 'RESTORE')),
    quantity_delta integer NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (order_id, kind),
    CHECK ((kind = 'DEBIT' AND quantity_delta < 0)
        OR (kind = 'RESTORE' AND quantity_delta > 0))
);

-- A request with no outcome may exist only inside its acceptance transaction.
CREATE FUNCTION require_finished_request() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM order_requests WHERE id = NEW.id AND outcome IS NULL) THEN
        RAISE EXCEPTION 'Order request outcome must be committed together' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER finished_request_at_commit
AFTER INSERT OR UPDATE ON order_requests DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION require_finished_request();
