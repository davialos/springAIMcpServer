package com.springaimcpservercommon.celfaker.importer;

import com.springaimcpservercommon.celfaker.contract.ApiSpec;

import java.util.List;

/**
 * Result of an import.
 *
 * @param title    document or command title
 * @param baseUrl  base URL of the service ({@code scheme://host:port}), empty when unknown
 * @param apis     the imported APIs
 * @param warnings things the user should review (secrets replaced by placeholders, unsupported parts)
 */
public record Imported(String title, String baseUrl, List<ApiSpec> apis, List<String> warnings) {
}
