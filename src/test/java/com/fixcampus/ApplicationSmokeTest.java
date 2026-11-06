package com.fixcampus;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses real HTTP and an isolated MySQL schema. */
class ApplicationSmokeTest extends RealMySqlTestSupport {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @LocalServerPort int port;

    @Test
    void connectsToRealMySql() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            assertThat(connection.getCatalog()).isNotBlank();
        }
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void rendersThymeleafPageAndServesLocalStyles() {
        var page = http.getForEntity("/login", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("<footer class=\"site-footer\">FixCampus — 校园报修管理平台</footer>", "欢迎回来", "name=\"_csrf\"", "href=\"/css/app.css\"");
        var css = http.getForEntity("/css/app.css", String.class);
        assertThat(css.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(css.getHeaders().getContentType().toString()).startsWith("text/css");
    }

    @Test
    void databaseGrantsUseLiteralSchemaNames() throws Exception {
        String catalog;
        try (var connection = dataSource.getConnection()) {
            catalog = connection.getCatalog();
        }
        boolean partialRevokes = Boolean.TRUE.equals(jdbc.queryForObject("SELECT @@GLOBAL.partial_revokes", Boolean.class));
        String grantName = partialRevokes ? catalog : catalog.replace("_", "\\_");
        var grants = jdbc.queryForList("SHOW GRANTS", String.class);
        assertThat(grants).anyMatch(grant -> grant.contains(" ON `" + grantName + "`.* TO "));
        if (!partialRevokes) {
            assertThat(grants).noneMatch(grant -> grant.contains(" ON `" + catalog + "`.* TO "));
        }
    }

    @Test
    void requiresLoginForRoleEntrances() throws Exception {
        var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        for (String route : new String[]{"/student/orders", "/admin/orders", "/worker/orders"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(URI.create(response.headers().firstValue("Location").orElseThrow()).getPath()).isEqualTo("/login");
        }
    }

    @Test
    void rejectsPostWithoutCsrfToken() {
        assertThat(http.postForEntity("/", null, String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
