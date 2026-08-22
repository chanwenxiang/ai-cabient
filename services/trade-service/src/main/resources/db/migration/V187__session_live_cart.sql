-- live cart for third-party vision push during SHOPPING (UX only; settlement uses final recognition/gravity)
ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS live_cart TEXT;
