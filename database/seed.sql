.
USE smartblock_db;
SET time_zone = '+05:30';

SET @has_required_workers = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'maintenance_task'
      AND COLUMN_NAME = 'required_workers'
);
SET @add_required_workers_sql = IF(
    @has_required_workers = 0,
    'ALTER TABLE maintenance_task ADD COLUMN required_workers INT NOT NULL DEFAULT 1 AFTER duration_min',
    'SELECT 1'
);
PREPARE add_required_workers_stmt FROM @add_required_workers_sql;
EXECUTE add_required_workers_stmt;
DEALLOCATE PREPARE add_required_workers_stmt;


SET @has_requested_team = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'maintenance_task'
      AND COLUMN_NAME = 'requested_team'
);
SET @add_requested_team_sql = IF(@has_requested_team = 0,
    'ALTER TABLE maintenance_task ADD COLUMN requested_team VARCHAR(10) NOT NULL DEFAULT ''ANY'' AFTER required_workers',
    'SELECT 1');
PREPARE add_requested_team_stmt FROM @add_requested_team_sql;
EXECUTE add_requested_team_stmt;
DEALLOCATE PREPARE add_requested_team_stmt;


CREATE TABLE IF NOT EXISTS worker_resource (
    worker_id INT AUTO_INCREMENT PRIMARY KEY,
    worker_code VARCHAR(10) NOT NULL UNIQUE,
    worker_name VARCHAR(60) NOT NULL,
    skill VARCHAR(40) NOT NULL,
    team_code VARCHAR(10) NOT NULL DEFAULT 'A',
    active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

SET @has_team_code = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'worker_resource'
      AND COLUMN_NAME = 'team_code'
);
SET @add_team_code_sql = IF(@has_team_code = 0,
    'ALTER TABLE worker_resource ADD COLUMN team_code VARCHAR(10) NOT NULL DEFAULT ''A'' AFTER skill',
    'SELECT 1');
PREPARE add_team_code_stmt FROM @add_team_code_sql;
EXECUTE add_team_code_stmt;
DEALLOCATE PREPARE add_team_code_stmt;

CREATE TABLE IF NOT EXISTS block_worker_assignment (
    assignment_id INT AUTO_INCREMENT PRIMARY KEY,
    block_id INT NOT NULL,
    worker_id INT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_block_worker (block_id, worker_id),
    KEY idx_assignment_worker (worker_id),
    CONSTRAINT fk_assignment_block FOREIGN KEY (block_id) REFERENCES block_schedule(block_id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_worker FOREIGN KEY (worker_id) REFERENCES worker_resource(worker_id)
) ENGINE=InnoDB;

INSERT IGNORE INTO department (dept_name) VALUES
    ('Engineering'), ('Signal'), ('Traction');

INSERT IGNORE INTO section (section_id, section_name, status) VALUES
    ('S1', 'North Track', 'AVAILABLE'),
    ('S2', 'Central Track', 'AVAILABLE'),
    ('S3', 'South Track', 'AVAILABLE');


INSERT INTO app_user (username, password_hash, role, dept_id) VALUES
    ('eng1', 'pbkdf2-sha256$120000$Djte51G1mdR2XtPOKnpMPw==$lSckBHcMgx5yeNIuDddEB3r8bkV59gH1bH33kosAZvk=', 'DEPARTMENT', (SELECT dept_id FROM department WHERE dept_name = 'Engineering')) ,
    ('signal1', 'pbkdf2-sha256$120000$EudePYQ47PWLawID+4Vj0Q==$ZAFJQoGHNS0/ubwJOusrisXw5mvHfolUiIPMoeUlbgc=', 'DEPARTMENT', (SELECT dept_id FROM department WHERE dept_name = 'Signal')) ,
    ('control', 'pbkdf2-sha256$120000$XmSF4dFnYEzfQKzoptqd9w==$iJ0GZNzSliLE/SmBAfualPPxYTKkQYJskToegS2IKXo=', 'CONTROL', NULL),
    ('traction1', 'pbkdf2-sha256$120000$SJb6tkvR4d/IOoDf97r6Ew==$ZSGGOtUCeWeyd6E+FlVnCkcBjXbfw86F6NXYGuK9cPQ=', 'DEPARTMENT', (SELECT dept_id FROM department WHERE dept_name = 'Traction'))
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    role = VALUES(role),
    dept_id = VALUES(dept_id);


INSERT INTO worker_resource (worker_code, worker_name, skill, team_code, active) VALUES
    ('W1', 'Engineering Team A', 'Track maintenance', 'A', TRUE),
    ('W4', 'Engineering Team B', 'Track maintenance', 'B', TRUE),
    ('W2', 'Signal Team A', 'Signal maintenance', 'A', TRUE),
    ('W5', 'Signal Team B', 'Signal maintenance', 'B', TRUE),
    ('W3', 'Traction Team A', 'Electrical maintenance', 'A', TRUE),
    ('W6', 'Traction Team B', 'Electrical maintenance', 'B', TRUE)
ON DUPLICATE KEY UPDATE
    worker_name = VALUES(worker_name),
    skill = VALUES(skill),
    team_code = VALUES(team_code),
    active = VALUES(active);
