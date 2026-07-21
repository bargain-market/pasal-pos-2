package com.pos.service;

import org.junit.Assert;
import org.junit.Test;

public class StoreServiceTest {

    @Test
    public void testUpdateStoreInfo() {
        StoreService service = StoreService.getInstance();
        
        String testStoreId = "test-store-id";
        String testStoreName = "Test Store Name " + System.currentTimeMillis();
        
        // Update store info
        service.updateStoreInfo(testStoreId, testStoreName);
        
        // Verify in-memory update
        Assert.assertEquals("Store name should be updated", testStoreName, service.getStoreName());
        Assert.assertEquals("Store ID should be updated", testStoreId, service.getStoreId());
    }
}
