package com.pos.service;

import com.pos.api.ApiClient;
import com.pos.api.dto.PosSubscriptionStatusResponse;
import com.pos.config.ConfigManager;
import org.junit.Before;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All dependencies are mocks: no local or remote database or network is opened. */
public class SubscriptionLeaseServiceTest {
    private ApiClient api;
    private ConfigManager config;
    private SubscriptionLeaseService service;
    private Map<String, String> values;

    @Before public void setup() {
        api = mock(ApiClient.class);
        config = mock(ConfigManager.class);
        values = new HashMap<>();
        values.put(SubscriptionLeaseService.KEY_LEASE_TOKEN, "previous-signed-lease");
        when(config.getIntProperty(anyString(), anyInt())).thenReturn(4000);
        when(config.getProperty(anyString(), anyString())).thenAnswer(i -> values.getOrDefault(i.getArgument(0), i.getArgument(1)));
        doAnswer(i -> { values.put(i.getArgument(0), i.getArgument(1)); return null; }).when(config).setProperty(anyString(), anyString());
        service = new SubscriptionLeaseService(api, config);
    }

    @Test public void explicitAuthDenialsRevokeOfflineAccess() throws Exception {
        for (int code : new int[] {401, 402, 403}) {
            when(api.get(eq("/pos/subscription"), eq(PosSubscriptionStatusResponse.class), anyInt()))
                .thenThrow(new ApiClient.ApiException("Denied", code));
            values.put(SubscriptionLeaseService.KEY_LEASE_TOKEN, "old");
            assertEquals(SubscriptionLeaseService.RefreshResult.NO_ACCESS, service.refreshLease());
            assertEquals("", values.get(SubscriptionLeaseService.KEY_LEASE_TOKEN));
        }
    }

    @Test public void upstreamFailurePreservesLease() throws Exception {
        when(api.get(eq("/pos/subscription"), eq(PosSubscriptionStatusResponse.class), anyInt()))
            .thenThrow(new ApiClient.ApiException("Unavailable", 503));
        assertEquals(SubscriptionLeaseService.RefreshResult.UNAVAILABLE, service.refreshLease());
        assertEquals("previous-signed-lease", values.get(SubscriptionLeaseService.KEY_LEASE_TOKEN));
    }

    @Test public void onlineOnlyResponseClearsPreviousLease() throws Exception {
        PosSubscriptionStatusResponse data = new PosSubscriptionStatusResponse();
        data.hasAccess = true;
        when(api.get(eq("/pos/subscription"), eq(PosSubscriptionStatusResponse.class), anyInt()))
            .thenReturn(new ApiClient.ApiResponse<>(data, 200));
        assertEquals(SubscriptionLeaseService.RefreshResult.ONLINE_ONLY, service.refreshLease());
        assertEquals("", values.get(SubscriptionLeaseService.KEY_LEASE_TOKEN));
    }

    @Test public void serverAccessDenialClearsPreviousLease() throws Exception {
        PosSubscriptionStatusResponse data = new PosSubscriptionStatusResponse();
        data.hasAccess = false;
        when(api.get(eq("/pos/subscription"), eq(PosSubscriptionStatusResponse.class), anyInt()))
            .thenReturn(new ApiClient.ApiResponse<>(data, 200));
        assertEquals(SubscriptionLeaseService.RefreshResult.NO_ACCESS, service.refreshLease());
        assertEquals("", values.get(SubscriptionLeaseService.KEY_LEASE_TOKEN));
    }
}
