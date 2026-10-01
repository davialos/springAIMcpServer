package com.springaimcpservercommon.loadtest.data;

import com.springaimcpservercommon.loadtest.model.Constraints;
import com.springaimcpservercommon.loadtest.model.ScalarSchema;
import com.springaimcpservercommon.loadtest.model.ScalarType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FieldKindClassifierTest {

    @ParameterizedTest(name = "{0} ({1}, {2}) -> {3}")
    @CsvSource(nullValues = "-", value = {
            "id, INTEGER, int64, ID",
            "customerId, INTEGER, int64, ID",
            "customer_id, STRING, -, ID",
            "paid, BOOLEAN, -, BOOLEAN",
            "valid, STRING, -, TEXT",
            "contact, STRING, email, EMAIL",
            "emailAddress, STRING, -, EMAIL",
            "X-Request-Id, STRING, -, UUID",
            "ref, STRING, uuid, UUID",
            "firstName, STRING, -, FIRST_NAME",
            "surname, STRING, -, LAST_NAME",
            "displayName, STRING, -, FULL_NAME",
            "username, STRING, -, USERNAME",
            "mobileNumber, STRING, -, PHONE",
            "addressLine1, STRING, -, STREET",
            "zipCode, STRING, -, POSTAL_CODE",
            "countryCode, STRING, -, COUNTRY_CODE",
            "shippingCountry, STRING, -, COUNTRY",
            "status, STRING, -, STATUS",
            "state, STRING, -, STATE",
            "createdAt, STRING, -, DATE_TIME",
            "dueDate, STRING, -, DATE",
            "dob, STRING, date, BIRTH_DATE",
            "price, NUMBER, double, PRICE",
            "totalAmount, INTEGER, -, PRICE",
            "quantity, INTEGER, int32, QUANTITY",
            "page, INTEGER, -, PAGE",
            "size, INTEGER, -, PAGE_SIZE",
            "age, INTEGER, -, AGE",
            "lat, NUMBER, -, LATITUDE",
            "sku, STRING, -, CODE",
            "orderNumber, STRING, -, CODE",
            "q, STRING, -, SEARCH",
            "description, STRING, -, DESCRIPTION",
            "website, STRING, -, URL",
            "productName, STRING, -, NAME",
            "password, STRING, -, PASSWORD",
            "shippingMethod, STRING, -, TEXT",
            "rating, INTEGER, -, RATING",
            "weight, NUMBER, -, NUMBER",
    })
    void classifies(String name, ScalarType type, String format, FieldKind expected) {
        assertThat(FieldKindClassifier.classify(name, ScalarSchema.of(type, format))).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void enumValuesWinOverTheName() {
        ScalarSchema s = new ScalarSchema(ScalarType.STRING, null, Constraints.NONE, List.of("A", "B"), null);
        assertThat(FieldKindClassifier.classify("email", s)).isEqualTo(FieldKind.ENUM);
    }
}
