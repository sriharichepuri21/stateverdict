-- Each service owns its own database and credentials, as in a real
-- microservice deployment. Local development credentials only.
CREATE USER orders WITH PASSWORD 'orders';
CREATE DATABASE orders OWNER orders;

CREATE USER payments WITH PASSWORD 'payments';
CREATE DATABASE payments OWNER payments;
