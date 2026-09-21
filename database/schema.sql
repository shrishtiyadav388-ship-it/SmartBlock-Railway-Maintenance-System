-- SmartBlock Database Schema
-- Database implementation will be added during development.
-- SMARTBLOCK DATABASE
-- Railway Maintenance Block Planning System
-- MySQL 8.x

CREATE DATABASE smartblock;
USE smartblock;


-- 1. USERS AND ACCESS CONTROL

-- Stores different user roles
CREATE TABLE roles (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(20) NOT NULL UNIQUE
);


-- Stores railway departments
CREATE TABLE departments (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
);


-- Stores login and user information
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(120) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,

    role_id INT NOT NULL,
    department_id INT NULL,

    is_active BOOLEAN DEFAULT TRUE,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (role_id) REFERENCES roles(id),
    FOREIGN KEY (department_id) REFERENCES departments(id)
);


-- 2. RAILWAY RESOURCES

-- Railway sections where maintenance work is performed
CREATE TABLE sections (
    id INT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    zone VARCHAR(50),

    is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE assets (
    id INT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    section_id INT,
    FOREIGN KEY (section_id) REFERENCES sections(id)
);

-- Stores train timings for each railway section
CREATE TABLE train_timetable (
    id INT AUTO_INCREMENT PRIMARY KEY,
    section_id INT NOT NULL,

    train_no VARCHAR(20) NOT NULL,
    arrival_time TIME NOT NULL,
    departure_time TIME NOT NULL,

    FOREIGN KEY (section_id) REFERENCES sections(id)
);


-- 3. MAINTENANCE REQUESTS

-- Main table used by the scheduling algorithm
-- Each maintenance request can be treated like a process in OS
CREATE TABLE maintenance_requests (
    id INT AUTO_INCREMENT PRIMARY KEY,

    department_id INT NOT NULL,
    section_id INT NOT NULL,

    work_description VARCHAR(255) NOT NULL,

    requested_start DATETIME NOT NULL,
    duration_minutes INT NOT NULL
        CHECK (duration_minutes > 0),

    priority ENUM(
        'Low',
        'Medium',
        'High',
        'Critical'
    ) NOT NULL,

    -- Values used by scheduling algorithms
    arrival_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    waiting_minutes INT DEFAULT 0,
    effective_priority DECIMAL(6,2) DEFAULT 0,

    status ENUM(
        'Pending',
        'Conflict',
        'Proposed',
        'Approved',
        'Revised',
        'Rejected'
    ) DEFAULT 'Pending',

    submitted_by INT NOT NULL,

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (department_id)
        REFERENCES departments(id),

    FOREIGN KEY (section_id)
        REFERENCES sections(id),

    FOREIGN KEY (asset_id)
        REFERENCES assets(id),

    FOREIGN KEY (submitted_by)
        REFERENCES users(id),

    INDEX idx_section_time (section_id, requested_start),
    INDEX idx_status (status)
);


-- 4. REQUEST STATUS HISTORY

-- Keeps track of status changes of a request
CREATE TABLE request_status_history (
    id INT AUTO_INCREMENT PRIMARY KEY,

    request_id INT NOT NULL,

    old_status VARCHAR(20),
    new_status VARCHAR(20) NOT NULL,

    reason VARCHAR(255),

    changed_by INT,
    changed_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (request_id)
        REFERENCES maintenance_requests(id),

    FOREIGN KEY (changed_by)
        REFERENCES users(id)
);


-- 5. CONFLICTS

-- Stores conflicts between maintenance requests
CREATE TABLE conflicts (
    id INT AUTO_INCREMENT PRIMARY KEY,

    section_id INT NOT NULL,

    request_id_1 INT NOT NULL,
    request_id_2 INT NOT NULL,

    reason VARCHAR(255) NOT NULL,

    detected_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    resolved BOOLEAN DEFAULT FALSE,

    FOREIGN KEY (section_id)
        REFERENCES sections(id),

    FOREIGN KEY (request_id_1)
        REFERENCES maintenance_requests(id),

    FOREIGN KEY (request_id_2)
        REFERENCES maintenance_requests(id)
);


-- 6. PLANNER RUNS

-- Stores every time the scheduling algorithm is executed
CREATE TABLE planning_runs (
    id INT AUTO_INCREMENT PRIMARY KEY,

    triggered_by INT NOT NULL,

    run_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    -- Stores which requests were considered in this planner run
    input_request_ids VARCHAR(500) NOT NULL,

    FOREIGN KEY (triggered_by)
        REFERENCES users(id)
);


-- 7. PROPOSED SCHEDULE

-- Temporary schedule created by the planner
-- Control Room can review it before approval
CREATE TABLE proposed_schedules (
    id INT AUTO_INCREMENT PRIMARY KEY,

    planning_run_id INT NOT NULL,
    request_id INT NOT NULL,

    section_id INT NOT NULL,
    asset_id INT NULL,

    allocated_start DATETIME NOT NULL,
    allocated_end DATETIME NOT NULL,

    -- Explains why a request was moved or rescheduled
    reschedule_reason VARCHAR(255),

    FOREIGN KEY (planning_run_id)
        REFERENCES planning_runs(id),

    FOREIGN KEY (request_id)
        REFERENCES maintenance_requests(id),

    FOREIGN KEY (section_id)
        REFERENCES sections(id),

    FOREIGN KEY (asset_id)
        REFERENCES assets(id)
);


-- 8. FINAL APPROVED SCHEDULE

-- Only approved schedules are stored here
CREATE TABLE block_schedules (
    id INT AUTO_INCREMENT PRIMARY KEY,

    request_id INT NOT NULL UNIQUE,

    section_id INT NOT NULL,
    asset_id INT NULL,

    allocated_start DATETIME NOT NULL,
    allocated_end DATETIME NOT NULL,

    approved_by INT NOT NULL,

    approved_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (request_id)
        REFERENCES maintenance_requests(id),

    FOREIGN KEY (section_id)
        REFERENCES sections(id),

    FOREIGN KEY (asset_id)
        REFERENCES assets(id),

    FOREIGN KEY (approved_by)
        REFERENCES users(id)
);


-- 9. AUDIT LOG

-- Keeps record of important actions performed in the system
CREATE TABLE audit_logs (
    id INT AUTO_INCREMENT PRIMARY KEY,

    action VARCHAR(50) NOT NULL,

    performed_by INT,

    details VARCHAR(500),

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (performed_by)
        REFERENCES users(id)
);


-- 10. AI CHAT HISTORY

-- Stores questions and answers from the SmartBlock AI assistant
CREATE TABLE chat_history (
    id INT AUTO_INCREMENT PRIMARY KEY,

    user_id INT NOT NULL,

    question VARCHAR(500) NOT NULL,

    -- Information taken from SmartBlock before generating the answer
    retrieved_context TEXT,

    answer TEXT NOT NULL,

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (user_id)
        REFERENCES users(id)
);
