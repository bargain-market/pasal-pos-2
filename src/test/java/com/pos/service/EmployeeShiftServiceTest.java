package com.pos.service;

import com.pos.database.DatabaseManager;
import com.pos.model.EmployeeShift;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.Assert.*;

public class EmployeeShiftServiceTest {

    private EmployeeShiftService service;
    private DatabaseManager dbManager;

    @Before
    public void setUp() throws SQLException {
        dbManager = DatabaseManager.getInstance();
        service = EmployeeShiftService.getInstance();
        
        LocalDate today = LocalDate.now();
        String todayStr = today.toString();
        
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            
            stmt.execute("DELETE FROM employee_shifts WHERE id LIKE 'test-unit-%'");
            conn.commit();
            
            stmt.execute("INSERT INTO employee_shifts (id, employee_id, employee_name, store_id, clock_in_at, status, synced, created_at) " +
                    "VALUES ('test-unit-1', 'emp-unit-1', 'Test User One', 'store-1', '" + todayStr + " 10:00:00', 'COMPLETED', FALSE, CURRENT_TIMESTAMP)");
            stmt.execute("UPDATE employee_shifts SET clock_out_at = '" + todayStr + " 18:00:00' WHERE id = 'test-unit-1'");
            
            stmt.execute("INSERT INTO employee_shifts (id, employee_id, employee_name, store_id, clock_in_at, status, synced, created_at) " +
                    "VALUES ('test-unit-2', 'emp-unit-2', 'Test User Two', 'store-1', '" + todayStr + " 11:00:00', 'ACTIVE', FALSE, CURRENT_TIMESTAMP)");
            
            conn.commit();
            
            // Debug: Print all records
            System.out.println("DEBUG: All records in employee_shifts after commit:");
            try (java.sql.ResultSet rs = stmt.executeQuery("SELECT * FROM employee_shifts")) {
                while (rs.next()) {
                    System.out.println("ID: " + rs.getString("id") + ", Name: " + rs.getString("employee_name") + ", ClockIn: " + rs.getTimestamp("clock_in_at"));
                }
            }
        }
    }

    @After
    public void tearDown() throws SQLException {
        try (Connection conn = dbManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM employee_shifts WHERE id LIKE 'test-unit-%'");
            conn.commit();
        }
    }

    @Test
    public void testGetShiftHistoryAll() throws SQLException {
        LocalDate today = LocalDate.now();
        List<EmployeeShift> history = service.getShiftHistory(null, today, today);
        System.out.println("DEBUG: Found " + history.size() + " shifts for " + today);
        
        long count = history.stream()
                .filter(s -> s.getEmployeeName() != null && s.getEmployeeName().startsWith("Test User"))
                .count();
        assertTrue("Should find at least 2 test shifts, found " + count, count >= 2);
    }

    @Test
    public void testGetShiftHistoryByName() throws SQLException {
        LocalDate today = LocalDate.now();
        List<EmployeeShift> history = service.getShiftHistory("Test User One", today, today);
        
        assertFalse("Should find shifts for Test User One", history.isEmpty());
        assertEquals("Test User One", history.get(0).getEmployeeName());
    }

    @Test
    public void testGetAllActiveShifts() throws SQLException {
        List<EmployeeShift> active = service.getAllActiveShifts();
        
        boolean found = active.stream()
                .anyMatch(s -> "Test User Two".equals(s.getEmployeeName()));
        
        assertTrue("Should find 'Test User Two' in active shifts", found);
    }
}
