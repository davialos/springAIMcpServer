package com.springaimcpservercommon.ruleengine.contract;

import com.google.protobuf.ByteString;
import com.springaimcpservercommon.ruleengine.contract.v1.ErrorResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginRequest;
import com.springaimcpservercommon.ruleengine.contract.v1.LoginResponse;
import com.springaimcpservercommon.ruleengine.contract.v1.Role;
import com.springaimcpservercommon.ruleengine.contract.v1.Session;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wire contract against bytes fixed outside both implementations ({@code testdata/login-response.hex}, written by a
 * hand-rolled encoder). The UI's TypeScript test reads the same file, so the two languages are proven to agree on the
 * wire format, including UTF-8 text, nested messages, enums and 64-bit integers.
 */
class ContractGoldenTest {

    private static final Path GOLDEN = Path.of("testdata/login-response.hex");

    private static LoginResponse golden() {
        return LoginResponse.newBuilder()
                .setSession(Session.newBuilder()
                        .setUserId("33333333-0000-0000-0000-000000000001")
                        .setUsername("admin")
                        .setDisplayName("Anaïs Müller")
                        .setRole(Role.ROLE_ADMIN)
                        .setTenantId("11111111-1111-1111-1111-111111111111")
                        .setTenantName("Acme Bank")
                        .setOrganizationId("22222222-2222-2222-2222-222222222222")
                        .setOrganizationName("Retail"))
                .setAccessToken("golden.access.token")
                .setExpiresAtEpochSeconds(1_893_456_000L)
                .build();
    }

    @Test
    void decodesTheGoldenBytesWithTheTenantAndOrganizationIds() throws IOException {
        byte[] bytes = HexFormat.of().parseHex(Files.readString(GOLDEN).strip());

        LoginResponse parsed = LoginResponse.parseFrom(bytes);

        assertThat(parsed).isEqualTo(golden());
        assertThat(parsed.getSession().getTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(parsed.getSession().getOrganizationId()).isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(parsed.getSession().getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(parsed.getSession().getDisplayName()).isEqualTo("Anaïs Müller");
    }

    @Test
    void encodesToExactlyTheGoldenBytes() throws IOException {
        String expected = Files.readString(GOLDEN).strip();

        assertThat(HexFormat.of().formatHex(golden().toByteArray())).isEqualTo(expected);
    }

    @Test
    void unknownFieldsFromANewerPeerAreKeptNotRejected() throws IOException {
        // field 99 (string) appended by a future version of the contract: proto3 readers must tolerate it
        byte[] future = ByteString.copyFrom(golden().toByteArray())
                .concat(ByteString.copyFrom(new byte[]{(byte) 0x9a, 0x06, 0x02, 'h', 'i'})).toByteArray();

        LoginResponse parsed = LoginResponse.parseFrom(future);

        assertThat(parsed.getSession().getTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(parsed.getUnknownFields().getSerializedSize()).isPositive();
    }

    @Test
    void anEmptyRequestParsesToEmptyCredentials() throws IOException {
        LoginRequest empty = LoginRequest.parseFrom(new byte[0]);

        assertThat(empty.getUsername()).isEmpty();
        assertThat(empty.getPassword()).isEmpty();
        assertThat(ErrorResponse.newBuilder().setCode("x").build().getMessage()).isEmpty();
        assertThat(Session.getDefaultInstance().getRole()).isEqualTo(Role.ROLE_UNSPECIFIED);
    }
}
