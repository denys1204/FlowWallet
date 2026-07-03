-- Create databases if they don't exist
SELECT 'CREATE DATABASE wallet_db'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'wallet_db')\gexec

SELECT 'CREATE DATABASE payment_db'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'payment_db')\gexec
