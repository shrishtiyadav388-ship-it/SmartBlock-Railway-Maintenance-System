

DROP DATABASE IF EXISTS smartblock_db;
CREATE DATABASE smartblock_db;
USE smartblock_db;

CREATE TABLE department (
    dept_id   INT AUTO_INCREMENT PRIMARY KEY,
    dept_name VARCHAR(50) NOT NULL UNIQUE
);


CREATE TABLE section (
    section_id   VARCHAR(50) PRIMARY KEY,
    section_name VARCHAR(50) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    CONSTRAINT chk_fixed_sections CHECK (section_id IN ('S1', 'S2', 'S3'))
);

CREATE TABLE app_user (
    user_id   INT AUTO_INCREMENT PRIMARY KEY,
    username  VARCHAR(40) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role      VARCHAR(20) NOT NULL,
    dept_id   INT NULL,
    CONSTRAINT chk_user_role CHECK (
        (role = 'CONTROL' AND dept_id IS NULL) OR
        (role = 'DEPARTMENT' AND dept_id IS NOT NULL)
    ),
    FOREIGN KEY (dept_id) REFERENCES department(dept_id)
);


CREATE TABLE task_id_sequence (
    sequence_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE maintenance_task (
    task_id      VARCHAR(10) PRIMARY KEY,
    dept_id      INT NOT NULL,
    section_id   VARCHAR(50) NOT NULL,
    work_date    DATE NOT NULL,
    work_desc    VARCHAR(100),
    priority     VARCHAR(10) NOT NULL,       
    duration_min INT NOT NULL,
    required_workers INT NOT NULL DEFAULT 1,
    requested_team VARCHAR(10) NOT NULL DEFAULT 'ANY',
    arrival_time VARCHAR(5) NOT NULL,        
    request_fingerprint CHAR(64) NOT NULL,
    status       VARCHAR(25) NOT NULL DEFAULT 'PENDING',
    decision_reason VARCHAR(500) NULL,
    status_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    completion_status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED', 
    completion_reported_at TIMESTAMP NULL DEFAULT NULL,
    completion_reported_by VARCHAR(40) NULL,
    completion_report_note VARCHAR(500) NULL,
    status_requested_at TIMESTAMP NULL DEFAULT NULL,
    status_requested_by VARCHAR(40) NULL,
    status_request_note VARCHAR(500) NULL,
    completed_at TIMESTAMP NULL DEFAULT NULL,
    completed_by VARCHAR(40) NULL,
    completion_note VARCHAR(500) NULL,
    supersedes_task_id VARCHAR(10) NULL,
    UNIQUE KEY uq_request_fingerprint (request_fingerprint),
    UNIQUE KEY uq_task_section (task_id, section_id),
    KEY idx_task_date_section (work_date, section_id),
    FOREIGN KEY (dept_id) REFERENCES department(dept_id),
    FOREIGN KEY (section_id) REFERENCES section(section_id),
    CONSTRAINT fk_task_supersedes FOREIGN KEY (supersedes_task_id) REFERENCES maintenance_task(task_id),
    CONSTRAINT chk_required_workers CHECK (required_workers BETWEEN 1 AND 2),
    CONSTRAINT chk_requested_team CHECK (requested_team IN ('A', 'B', 'ANY'))
);


-- Shared worker crews are database resources. InnoDB row locks protect them
-- when concurrent jobs try to acquire workers.
CREATE TABLE worker_resource (
    worker_id INT AUTO_INCREMENT PRIMARY KEY,
    worker_code VARCHAR(10) NOT NULL UNIQUE,
    worker_name VARCHAR(60) NOT NULL,
    skill VARCHAR(40) NOT NULL,
    team_code VARCHAR(10) NOT NULL DEFAULT 'A',
    active BOOLEAN NOT NULL DEFAULT TRUE
) ENGINE=InnoDB;

CREATE TABLE completion_history (
    completion_event_id INT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(10) NOT NULL,
    event_type VARCHAR(20) NOT NULL, 
    performed_by VARCHAR(40) NOT NULL,
    event_note VARCHAR(500) NULL,
    event_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_completion_task_time (task_id, event_time),
    CONSTRAINT fk_completion_history_task FOREIGN KEY (task_id) REFERENCES maintenance_task(task_id)
);

CREATE TABLE request_decision_history (
    decision_id INT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(10) NOT NULL,
    previous_status VARCHAR(25) NOT NULL,
    new_status VARCHAR(25) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    changed_by VARCHAR(40) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_decision_task_time (task_id, changed_at),
    CONSTRAINT fk_decision_task FOREIGN KEY (task_id) REFERENCES maintenance_task(task_id)
);

CREATE TABLE block_request (
    request_id      INT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(10) NOT NULL UNIQUE,
    section_id      VARCHAR(50) NOT NULL,
    requested_start VARCHAR(5) NOT NULL,
    requested_end   VARCHAR(5) NOT NULL,
    CONSTRAINT chk_request_time_order CHECK (requested_start < requested_end),
    FOREIGN KEY (task_id, section_id) REFERENCES maintenance_task(task_id, section_id),
    FOREIGN KEY (section_id) REFERENCES section(section_id)
);

CREATE INDEX idx_request_section_time
    ON block_request(section_id, requested_start, requested_end);

CREATE TABLE block_schedule (
    block_id    INT AUTO_INCREMENT PRIMARY KEY,
    section_id  VARCHAR(50) NOT NULL,
    task_id     VARCHAR(10) NOT NULL UNIQUE,
    schedule_date DATE NOT NULL,
    start_time  VARCHAR(5) NOT NULL,
    end_time    VARCHAR(5) NOT NULL,
    approved_by VARCHAR(40),
    FOREIGN KEY (section_id) REFERENCES section(section_id),
    FOREIGN KEY (task_id) REFERENCES maintenance_task(task_id)
);

-- Actual named-worker reservations for approved maintenance blocks.
-- A worker may not be assigned to overlapping blocks; the approval transaction
-- locks the qualified worker rows and inserts these records atomically.
CREATE TABLE block_worker_assignment (
    assignment_id INT AUTO_INCREMENT PRIMARY KEY,
    block_id INT NOT NULL,
    worker_id INT NOT NULL,
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_block_worker (block_id, worker_id),
    KEY idx_assignment_worker (worker_id),
    CONSTRAINT fk_assignment_block FOREIGN KEY (block_id) REFERENCES block_schedule(block_id) ON DELETE CASCADE,
    CONSTRAINT fk_assignment_worker FOREIGN KEY (worker_id) REFERENCES worker_resource(worker_id)
) ENGINE=InnoDB;

CREATE TABLE train_schedule (
    schedule_id  INT AUTO_INCREMENT PRIMARY KEY,
    train_no     VARCHAR(10) NOT NULL,
    train_type   VARCHAR(15),
    section_id   VARCHAR(50) NOT NULL,
    service_date DATE NOT NULL,
    pass_start   VARCHAR(5),
    pass_end     VARCHAR(5),
    FOREIGN KEY (section_id) REFERENCES section(section_id)
);

CREATE TABLE conflict_log (
    conflict_id INT AUTO_INCREMENT PRIMARY KEY,
    section_id  VARCHAR(50) NOT NULL,
    work_date   DATE NOT NULL,
    task_id_1   VARCHAR(10) NOT NULL,
    task_id_2   VARCHAR(10) NOT NULL,
    detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_conflict_pair (section_id, work_date, task_id_1, task_id_2)
);


CREATE TABLE audit_log (
    log_id       INT AUTO_INCREMENT PRIMARY KEY,
    block_id     INT,
    action       VARCHAR(50) NOT NULL,
    performed_by VARCHAR(40),
    log_time     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

DELIMITER $$
CREATE TRIGGER validate_block_request_before_insert
BEFORE INSERT ON block_request
FOR EACH ROW
BEGIN
    DECLARE v_duration INT DEFAULT 0;
    DECLARE v_window INT DEFAULT 0;
    SELECT duration_min INTO v_duration
      FROM maintenance_task WHERE task_id = NEW.task_id;
    SET v_window = TIMESTAMPDIFF(MINUTE,
        STR_TO_DATE(NEW.requested_start, '%H:%i'),
        STR_TO_DATE(NEW.requested_end, '%H:%i'));
    IF NEW.requested_start >= NEW.requested_end THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Requested end must be after requested start';
    ELSEIF v_window < v_duration THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Duration must fit inside the requested time window';
    END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER after_block_approved
AFTER INSERT ON block_schedule
FOR EACH ROW
BEGIN
    UPDATE maintenance_task SET status = 'APPROVED' WHERE task_id = NEW.task_id;
    INSERT INTO audit_log (block_id, action, performed_by)
    VALUES (NEW.block_id, 'BLOCK_APPROVED', NEW.approved_by);
END$$
DELIMITER ;

CREATE VIEW active_block_view AS
SELECT b.block_id, b.section_id, b.task_id, b.schedule_date, t.work_desc,
       d.dept_name, t.priority, b.start_time, b.end_time
FROM block_schedule b
JOIN maintenance_task t ON t.task_id = b.task_id
JOIN department d       ON d.dept_id = t.dept_id;

CREATE VIEW department_work_view AS
SELECT d.dept_name,
       COUNT(*)              AS total_tasks,
       SUM(t.duration_min)   AS total_minutes
FROM maintenance_task t
JOIN department d ON d.dept_id = t.dept_id
GROUP BY d.dept_name;

CREATE VIEW section_utilization_view AS
SELECT s.section_id, s.section_name, s.status, COUNT(b.block_id) AS blocks_scheduled
FROM section s
LEFT JOIN block_schedule b ON b.section_id = s.section_id
GROUP BY s.section_id, s.section_name, s.status;

CREATE INDEX idx_task_section ON maintenance_task(section_id);

DELIMITER $$
CREATE PROCEDURE sp_approve_block(
    IN  p_task_id    VARCHAR(10),
    IN  p_section_id VARCHAR(50),
    IN  p_date       DATE,
    IN  p_start      VARCHAR(5),
    IN  p_end        VARCHAR(5),
    IN  p_by         VARCHAR(40),
    OUT p_block_id   INT,
    OUT p_message    VARCHAR(100)
)
BEGIN
    DECLARE v_block_overlap INT DEFAULT 0;
    DECLARE v_train_overlap INT DEFAULT 0;
    DECLARE v_task_ok INT DEFAULT 0;
    DECLARE v_section_ok INT DEFAULT 0;
    DECLARE v_task_status VARCHAR(25);
    DECLARE v_locked_section VARCHAR(50);
    DECLARE v_requested_start VARCHAR(5);
    DECLARE v_requested_end VARCHAR(5);
    DECLARE v_arrival_time VARCHAR(5);
    DECLARE v_duration INT DEFAULT 0;

    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    SELECT COUNT(*) INTO v_section_ok FROM section WHERE section_id = p_section_id;
    IF v_section_ok > 0 THEN
        SELECT section_id INTO v_locked_section
        FROM section WHERE section_id = p_section_id FOR UPDATE;
    END IF;

    SELECT COUNT(*) INTO v_task_ok
    FROM maintenance_task t
    JOIN block_request r ON r.task_id = t.task_id AND r.section_id = t.section_id
    WHERE t.task_id = p_task_id
      AND t.section_id = p_section_id
      AND t.work_date = p_date;

    IF v_task_ok > 0 THEN
        SELECT t.status, r.requested_start, r.requested_end, t.arrival_time, t.duration_min
          INTO v_task_status, v_requested_start, v_requested_end, v_arrival_time, v_duration
        FROM maintenance_task t
        JOIN block_request r ON r.task_id = t.task_id AND r.section_id = t.section_id
        WHERE t.task_id = p_task_id AND t.section_id = p_section_id AND t.work_date = p_date
        FOR UPDATE;
    END IF;

    SELECT COUNT(*) INTO v_block_overlap
    FROM block_schedule
    WHERE section_id = p_section_id
      AND schedule_date = p_date
      AND start_time < p_end
      AND end_time > p_start;

    SELECT COUNT(*) INTO v_train_overlap
    FROM train_schedule
    WHERE section_id = p_section_id
      AND service_date = p_date
      AND pass_start IS NOT NULL
      AND pass_end IS NOT NULL
      AND pass_start < p_end
      AND pass_end > p_start;

    IF p_start IS NULL OR p_end IS NULL
       OR p_start NOT REGEXP '^([01][0-9]|2[0-3]):[0-5][0-9]$'
       OR p_end NOT REGEXP '^([01][0-9]|2[0-3]):[0-5][0-9]$'
       OR p_start >= p_end THEN
        SET p_block_id = -1;
        SET p_message = 'Enter valid times and make sure end is after start';
    ELSEIF v_section_ok = 0 THEN
        SET p_block_id = -1;
        SET p_message = 'Section is not registered';
    ELSEIF p_date < CURDATE() THEN
        SET p_block_id = -1;
        SET p_message = 'A past maintenance date cannot be approved';
    ELSEIF p_date = CURDATE() AND p_start < DATE_FORMAT(CURTIME(), '%H:%i') THEN
        SET p_block_id = -1;
        SET p_message = 'The requested start time has already passed today';
    ELSEIF v_task_ok = 0 OR v_task_status <> 'PENDING' THEN
        SET p_block_id = -1;
        SET p_message = 'Task is missing, belongs to another section, or is no longer pending';
    ELSEIF p_start < v_requested_start OR p_end > v_requested_end OR p_start < v_arrival_time THEN
        SET p_block_id = -1;
        SET p_message = 'Approved time must respect arrival time and requested window';
    ELSEIF TIMESTAMPDIFF(MINUTE, STR_TO_DATE(p_start, '%H:%i'), STR_TO_DATE(p_end, '%H:%i')) < v_duration THEN
        SET p_block_id = -1;
        SET p_message = 'Approved slot is shorter than the maintenance duration';
    ELSEIF v_block_overlap > 0 THEN
        SET p_block_id = -1;
        SET p_message = 'Time overlaps an approved block';
    ELSEIF v_train_overlap > 0 THEN
        SET p_block_id = -1;
        SET p_message = 'Time overlaps a train movement window';
    ELSE
        INSERT INTO block_schedule (section_id, task_id, schedule_date, start_time, end_time, approved_by)
        VALUES (p_section_id, p_task_id, p_date, p_start, p_end, p_by);
        SET p_block_id = LAST_INSERT_ID();
        SET p_message = 'OK';
    END IF;

    COMMIT;
END$$
DELIMITER ;

