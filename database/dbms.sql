-- Use the SmartBlock database
USE smartblock;


SELECT *
FROM requests
WHERE department = 'Engineering'
  AND status = 'PENDING';


-- View to display approved maintenance schedules
CREATE OR REPLACE VIEW approved_schedule AS
SELECT
    r.id,
    s.name AS section,
    d.name AS department,
    r.requested_start AS starts,
    TIMESTAMPADD(MINUTE, r.duration_minutes, r.requested_start) AS ends
FROM maintenance_requests r
JOIN sections s ON s.id = r.section_id
JOIN departments d ON d.id = r.department_id
WHERE r.status = 'Approved';


-- Show total requests and approved requests for each section
SELECT
    s.name,
    COUNT(r.id) AS requests,
    SUM(CASE
        WHEN r.status = 'APPROVED' THEN 1
        ELSE 0
    END) AS approved
FROM sections s
LEFT JOIN requests r
    ON r.section_id = s.id
GROUP BY s.id, s.name;


-- Stored procedure to show request status for a department
DELIMITER //

CREATE PROCEDURE department_summary(IN department_name VARCHAR(20))
BEGIN
    SELECT
        status,
        COUNT(*) AS total
    FROM requests
    WHERE department = department_name
    GROUP BY status;
END//


-- Trigger to make sure approved/rejected requests have a reason
CREATE TRIGGER require_decision_reason
BEFORE UPDATE ON requests
FOR EACH ROW
BEGIN
    IF NEW.status IN ('APPROVED', 'REJECTED')
       AND LENGTH(TRIM(NEW.reason)) = 0 THEN

        SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'Decision reason required';

    END IF;
END//

DELIMITER ;


-- Test the stored procedure
CALL department_summary('Engineering');


-- Display approved schedules in starting-time order
SELECT *
FROM approved_schedule
ORDER BY starts;
