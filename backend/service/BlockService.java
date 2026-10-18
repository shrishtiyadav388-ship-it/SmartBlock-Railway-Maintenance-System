package com.smartblock.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.CallableStatement;
import java.sql.Date;
import java.sql.Types;
import java.time.LocalDate;

@Service
public class BlockService {

    private final JdbcTemplate jdbc;

    public BlockService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static class SectionBusyException extends RuntimeException {
        public SectionBusyException(String message) { super(message); }
    }

    public int approveBlock(String taskId, String sectionId, LocalDate workDate, String start, String end, String by) {
        return jdbc.execute((java.sql.Connection con) -> {
            CallableStatement cs = con.prepareCall("{call sp_approve_block(?,?,?,?,?,?,?,?)}");
            cs.setString(1, taskId);
            cs.setString(2, sectionId);
            cs.setDate(3, Date.valueOf(workDate));
            cs.setString(4, start);
            cs.setString(5, end);
            cs.setString(6, by);
            cs.registerOutParameter(7, Types.INTEGER);
            cs.registerOutParameter(8, Types.VARCHAR);
            cs.execute();

            String message = cs.getString(8);
            if (!"OK".equals(message)) {
                throw new SectionBusyException(message);
            }
            return cs.getInt(7);
        });
    }

}
