package com.example.logistics.order;

import com.example.logistics.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class OrderApiIT extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    static final String BODY = """
        {"customerId":"CUST-9","quantity":2,
         "networkId":"NET-FEDEX","networkName":"FedEx Global",
         "hubId":"HUB-EU","hubName":"European Hub",
         "fleetId":"FLT-EU-AIR","fleetName":"Express Flight Fleet","fleetMode":"Air",
         "containerId":"CON-80","containerName":"Freight Box #CON-80",
         "packageId":"PKG-2001","packageDescription":"Box with tracking ID #PKG-2001","weightKg":3.2}
        """;

    @Test
    void postOrder_thenTrace() throws Exception {
        String response = mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packageId").value("PKG-2001"))
                .andReturn().getResponse().getContentAsString();

        String orderNo = com.jayway.jsonpath.JsonPath.read(response, "$.orderNo");

        mvc.perform(get("/orders/" + orderNo + "/trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network.name").value("FedEx Global"))
                .andExpect(jsonPath("$.hub.id").value("HUB-EU"))
                .andExpect(jsonPath("$.package.containerId").value("CON-80"));
    }

    @Test
    void prometheusEndpoint_exposesCustomMetrics() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk());

        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
    }
}
