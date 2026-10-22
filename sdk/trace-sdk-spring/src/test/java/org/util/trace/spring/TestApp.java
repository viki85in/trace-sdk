package org.util.trace.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.util.trace.spring.Fixtures.FinalService;
import org.util.trace.spring.Fixtures.InventoryService;
import org.util.trace.spring.Fixtures.OrderService;
import org.util.trace.spring.Fixtures.PaymentGateway;

@Configuration(proxyBeanMethods = false)
class TestApp {

    @Bean
    InventoryService inventoryService() {
        return new InventoryService();
    }

    @Bean
    PaymentGateway paymentGateway() {
        return new PaymentGateway();
    }

    @Bean
    OrderService orderService(InventoryService inventory, PaymentGateway payments) {
        return new OrderService(inventory, payments);
    }

    @Bean
    FinalService finalService() {
        return new FinalService();
    }
}
