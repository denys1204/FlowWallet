package com.flowwallet.wallet.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseAmountsTest {
    private static final Set<Class<?>> NUMBER_TYPES = Set.of(
            BigDecimal.class,
            double.class,
            Double.class,
            float.class,
            Float.class
    );

    @Test
    void noResponseCarriesMoneyAsAJsonNumber() {
        // Guards a response record that writes an amount as a JSON number: Jackson would print it at the ledger's
        // scale of 4, and a JavaScript client would read it as a float. Money in a response is a decimal string from
        // AmountPrecision.render (docs/adr/0030-amounts-in-responses-are-decimal-strings.md). The scan finds every
        // record named *Response in the wallet, so a new one is checked without being listed here.
        List<Class<?>> responses = responseRecords();

        assertThat(responses)
                .extracting(Class::getSimpleName)
                .contains("WalletResponse", "BalanceHistoryResponse", "TransferResponse");
        assertThat(responses)
                .flatExtracting(type -> Arrays.asList(type.getRecordComponents()))
                .filteredOn(component -> NUMBER_TYPES.contains(((RecordComponent) component).getType()))
                .isEmpty();
    }

    private static List<Class<?>> responseRecords() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*Response")));
        return scanner.findCandidateComponents("com.flowwallet.wallet").stream()
                .map(BeanDefinition::getBeanClassName)
                .<Class<?>>map(ResponseAmountsTest::load)
                .filter(Class::isRecord)
                .toList();
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Scanned a class that cannot be loaded: " + className, e);
        }
    }
}
