package com.inttelgo.mspse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inttelgo.mspse.domain.TransactionStatus;
import com.inttelgo.mspse.exception.ApiException;
import com.inttelgo.mspse.repository.TransaccionRepository;
import com.inttelgo.mspse.service.BuildClient;
import com.inttelgo.mspse.service.PendingTransactionsJob;
import com.inttelgo.mspse.service.PseClient;
import com.inttelgo.mspse.service.RecaptchaClient;
import com.inttelgo.mspse.service.ServiceLookup;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PseApiTest {

    private static final String USER = "7";

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    TransaccionRepository transactionRepository;

    @Autowired
    PendingTransactionsJob job;

    @MockitoBean
    PseClient pseClient;

    @MockitoBean
    RecaptchaClient recaptcha;

    @MockitoBean
    BuildClient buildClient;

    @MockitoBean
    ServiceLookup serviceLookup;

    @BeforeEach
    void setUp() {
        // 10 activo (pago anticipado), 11 en corte (mes actual), 12 de otro cliente.
        when(serviceLookup.byIds(anyCollection())).thenReturn(Map.of(
                10L, new ServiceLookup.ServiceInfo(500L, 1L),
                11L, new ServiceLookup.ServiceInfo(500L, 2L),
                12L, new ServiceLookup.ServiceInfo(900L, 1L)));
        when(serviceLookup.extras(10L)).thenReturn(Map.of(5L, new ServiceLookup.ExtraInfo(5L, "Pantalla WIN+", new BigDecimal("15000"))));
        when(buildClient.categoryStatuses(1L)).thenReturn(List.of(2L, 6L, 7L));
        when(buildClient.categoryStatuses(2L)).thenReturn(List.of(1L));
        when(buildClient.categoryStatuses(3L)).thenReturn(List.of(8L, 11L));
        when(buildClient.collection(eq(30L))).thenReturn(new BuildClient.Collection(30L, 1L, 3, 1, 1, 500L));
        when(buildClient.collection(eq(31L))).thenReturn(new BuildClient.Collection(31L, 1L, 2, 2, 2, 500L));
        when(buildClient.createBuild(eq(500L), any())).thenReturn(77L);
        when(buildClient.createPayment(eq(30L), anyInt(), any(), anyString())).thenReturn(88L);
        when(pseClient.createTransaction(any())).thenReturn(json(
                "{\"returnCode\":\"SUCCESS\",\"trazabilityCode\":\"CUS-1\",\"pseURL\":\"https://pse.test/pay\",\"transactionCycle\":\"1\"}"));
    }

    @Test
    void approvedTransactionCreatesBuildAndPaymentOnce() throws Exception {
        // 10% sobre 60.050 = 54.045, redondeado hacia arriba a la centena como ms-build = 54.100.
        String hash = start("\"discount\":10," + body(
                "{\"description\":\"Mensualidad\",\"service_id\":10,\"internet_value\":60050,\"value\":60050}",
                "{\"service_id\":10,\"extra_id\":5,\"value\":15000}",
                "{\"description\":\"Cuota modem\",\"collection_build_id\":30,\"value\":20000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code_trazability").value("CUS-1"))
                .andExpect(jsonPath("$.data.pse_url").value("https://pse.test/pay"))
                .andReturn().getResponse().getContentAsString().transform(this::ticketHash);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> sent = ArgumentCaptor.forClass(Map.class);
        verify(pseClient).createTransaction(sent.capture());
        // El % no aplica al extra (ms-build no lo descuenta): 54.100 + 15.000 + 20.000.
        assertEquals("89100", sent.getValue().get("transactionValue"));
        java.time.YearMonth month = java.time.YearMonth.now().plusMonths(1);
        String monthName = month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.forLanguageTag("es-CO"));
        String description = (String) sent.getValue().get("paymentDescription");
        assertTrue(description.length() <= 80, description);
        assertTrue(description.startsWith("Factura " + monthName + " " + month.getYear() + " - 1 servicio - 1 extra - Descuento 10%"), description);
        assertTrue(description.endsWith(" - 1 pago adicional"), description);
        assertEquals("https://landing.test/pse/verify/" + hash, sent.getValue().get("entityurl"));
        assertEquals("901132952", sent.getValue().get("entityCode"));

        when(pseClient.transactionInfo("CUS-1")).thenReturn(json("{\"transactionState\":\"PENDING\"}"));
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.status").value("PENDIENTE"));
        verify(buildClient, never()).createBuild(any(), any());

        when(pseClient.transactionInfo("CUS-1")).thenReturn(json(
                "{\"transactionState\":\"OK\",\"bankProcessDate\":\"2026-11-06T10:15:00\",\"transactionCycle\":\"2\",\"financialInstitutionCode\":\"1022\"}"));
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.status").value("APROBADA"))
                .andExpect(jsonPath("$.data.build_id").value(77))
                .andExpect(jsonPath("$.data.transaction_value").value(89100.0))
                .andExpect(jsonPath("$.data.discount").value(10))
                .andExpect(jsonPath("$.data.create_by").value(119))
                .andExpect(jsonPath("$.data.details[0].value").value(54100.0))
                .andExpect(jsonPath("$.data.details[0].discount").value(5950.0))
                .andExpect(jsonPath("$.data.details[1].extra_id").value(5))
                .andExpect(jsonPath("$.data.details[1].description").value("Pantalla WIN+"))
                .andExpect(jsonPath("$.data.details[1].value").value(15000.0))
                .andExpect(jsonPath("$.data.details[1].discount").doesNotExist())
                .andExpect(jsonPath("$.data.details[2].value").value(20000.0))
                .andExpect(jsonPath("$.data.financial_institutionCode").value("1022"))
                .andExpect(jsonPath("$.data.details[0].service_id").value(10))
                .andExpect(jsonPath("$.data.details[0].payment_id").doesNotExist())
                .andExpect(jsonPath("$.data.details[2].payment_id").value(88));

        ArgumentCaptor<BuildClient.BuildRequest> build = ArgumentCaptor.forClass(BuildClient.BuildRequest.class);
        verify(buildClient).createBuild(eq(500L), build.capture());
        assertEquals(2, build.getValue().details().size());
        BuildClient.BuildLine extraLine = build.getValue().details().get(1);
        assertEquals(10L, extraLine.serviceId());
        assertEquals(5L, extraLine.extraId());
        assertEquals("Pantalla WIN+", extraLine.description());
        assertEquals(0, new BigDecimal("15000").compareTo(extraLine.value()));
        // ms-build recibe el valor sin descuento y el %, y llega al mismo 54.100.
        assertEquals(0, new BigDecimal("60050").compareTo(build.getValue().details().get(0).value()));
        assertEquals(0, BigDecimal.TEN.compareTo(build.getValue().discount()));
        assertEquals(LocalDate.now().plusMonths(1).withDayOfMonth(1), build.getValue().buildAt());
        verify(buildClient).createPayment(eq(30L), eq(2), any(), anyString());
        verify(pseClient).finalizeTransaction("CUS-1");

        // Ya aprobada: no vuelve a consultar PSE ni a crear en ms-build.
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash)).andExpect(status().isOk());
        job.run();
        verify(pseClient, times(2)).transactionInfo(anyString());
        verify(buildClient, times(1)).createBuild(any(), any());
        verify(buildClient, times(1)).createPayment(any(), anyInt(), any(), anyString());
    }

    @Test
    void failedBuildIsRetriedByJob() throws Exception {
        when(buildClient.createBuild(eq(500L), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "BUILD_ERROR", "ms-build caído"))
                .thenReturn(78L);
        String hash = ticketHash(start(body("{\"service_id\":10,\"value\":50000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        when(pseClient.transactionInfo("CUS-1")).thenReturn(json("{\"transactionState\":\"OK\"}"));

        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash))
                .andExpect(jsonPath("$.data.status.status").value("APROBADA"))
                .andExpect(jsonPath("$.data.build_id").doesNotExist());

        job.run();
        Long id = transactionRepository.findAll().get(0).getId();
        mvc.perform(get("/api/v1/pse/transactions/{id}", id).header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.build_id").value(78));
        verify(buildClient, times(2)).createBuild(any(), any());
    }

    @Test
    void rejectedAndFailedStatesAreMapped() throws Exception {
        String hash = ticketHash(start(body("{\"service_id\":10,\"value\":50000}")).andReturn().getResponse().getContentAsString());
        when(pseClient.transactionInfo("CUS-1")).thenReturn(json("{\"transactionState\":\"NOT_AUTHORIZED\"}"));
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash))
                .andExpect(jsonPath("$.data.status.status").value("RECHAZADA"))
                .andExpect(jsonPath("$.data.response_at").exists());
        verify(buildClient, never()).createBuild(any(), any());
        verify(pseClient, never()).finalizeTransaction(anyString());
    }

    @Test
    void pseUnreachableKeepsLocalState() throws Exception {
        String hash = ticketHash(start(body("{\"service_id\":10,\"value\":50000}")).andReturn().getResponse().getContentAsString());
        when(pseClient.transactionInfo("CUS-1"))
                .thenThrow(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PSE_REQUEST_TIMEOUT", "timeout"));
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", hash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.status").value("PENDIENTE"));
    }

    @Test
    void pseRejectionOnStartMarksFailed() throws Exception {
        when(pseClient.createTransaction(any())).thenReturn(json("{\"returnCode\":\"FAIL_EXCEEDEDLIMIT\"}"));
        start(body("{\"service_id\":10,\"value\":50000}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("PSE_TRANSACTION_REJECTED"));
        assertTrue(TransactionStatus.FALLIDA.is(transactionRepository.findAll().get(0).getStatus()));
    }

    @Test
    void validatesLines() throws Exception {
        start(body("{\"service_id\":10,\"collection_build_id\":30,\"value\":1000}")).andExpect(status().isBadRequest());
        start(body("{\"value\":1000}")).andExpect(status().isBadRequest());
        start(body("{\"service_id\":10,\"value\":1000}", "{\"service_id\":12,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Los servicios de una transacción deben ser del mismo cliente"));
        start(body("{\"service_id\":99,\"value\":1000}")).andExpect(status().isNotFound());
        start(body("{\"service_id\":10,\"value\":1000}", "{\"service_id\":11,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("mismo periodo")));
        start(body("{\"collection_build_id\":31,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("El cobro 31 ya tiene todas sus cuotas pagadas"));
        start(body("{\"extra_id\":5,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Un extra va con el service_id al que pertenece"));
        start(body("{\"service_id\":10,\"extra_id\":6,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("El extra 6 no está asociado al servicio 10"));
        start(body("{\"service_id\":10,\"extra_id\":5,\"value\":1000}", "{\"service_id\":10,\"extra_id\":5,\"value\":1000}"))
                .andExpect(status().isBadRequest());
        start("\"discount\":10," + body("{\"service_id\":10,\"extra_id\":5,\"value\":1000}"))
                .andExpect(status().isBadRequest());
        start("\"discount\":0," + body("{\"service_id\":10,\"value\":1000}")).andExpect(status().isBadRequest());
        start("\"discount\":101," + body("{\"service_id\":10,\"value\":1000}")).andExpect(status().isBadRequest());
        start("\"discount\":10," + body("{\"collection_build_id\":30,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("El descuento solo aplica a pagos de factura (líneas con service_id)"));
        start("\"discount\":100," + body("{\"service_id\":10,\"value\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("El valor a pagar debe ser mayor a cero"));
        verify(pseClient, never()).createTransaction(any());
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void urlPaymentFlow() throws Exception {
        String created = mvc.perform(post("/api/v1/pse/urls").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concept\":\"Traslado\",\"value\":35000,\"expiry_at\":\"" + LocalDate.now().plusDays(3) + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status_id").value(1))
                .andExpect(jsonPath("$.data.status.status").value("ACTIVA"))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(created, "$.data.url");
        assertTrue(url.startsWith("https://landing.test/pse?payment="), url);
        String payment = url.substring(url.indexOf('=') + 1);

        mvc.perform(get("/api/v1/pse/urls/public/{hash}", payment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.concept").value("Traslado"))
                .andExpect(jsonPath("$.data.value").value(35000.0));

        String ticket = ticketHash(start("\"url_generated\":\"" + payment + "\"")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        when(pseClient.transactionInfo("CUS-1")).thenReturn(json("{\"transactionState\":\"OK\"}"));
        mvc.perform(get("/api/v1/pse/transactions/verify/{hash}", ticket))
                .andExpect(jsonPath("$.data.status.status").value("APROBADA"))
                .andExpect(jsonPath("$.data.transaction_value").value(35000.0))
                .andExpect(jsonPath("$.data.payment_description").value("Traslado"))
                .andExpect(jsonPath("$.data.details").isEmpty());

        mvc.perform(get("/api/v1/pse/urls/public/{hash}", payment))
                .andExpect(status().isBadRequest());
        Integer urlId = JsonPath.read(created, "$.data.id");
        mvc.perform(get("/api/v1/pse/urls/{id}", urlId).header("X-User-Id", USER))
                .andExpect(jsonPath("$.data.status_id").value(2))
                .andExpect(jsonPath("$.data.status.status").value("PAGADA"));
        mvc.perform(put("/api/v1/pse/urls/{id}", urlId).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"value\":1}"))
                .andExpect(status().isConflict());
        verify(buildClient, never()).createBuild(any(), any());
    }

    @Test
    void cancelledOrExpiredUrlCannotBePaid() throws Exception {
        String created = mvc.perform(post("/api/v1/pse/urls").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concept\":\"X\",\"value\":1000,\"expiry_at\":\"" + LocalDate.now() + "\"}"))
                .andReturn().getResponse().getContentAsString();
        Integer id = JsonPath.read(created, "$.data.id");
        String url = JsonPath.read(created, "$.data.url");
        String payment = url.substring(url.indexOf('=') + 1);
        mvc.perform(put("/api/v1/pse/urls/{id}", id).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status_id\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/pse/urls/{id}", id).header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status_id\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status.status").value("DESHABILITADA"));
        start("\"url_generated\":\"" + payment + "\"").andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/pse/urls").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concept\":\"X\",\"value\":1000,\"expiry_at\":\"" + LocalDate.now().minusDays(1) + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pse/urls").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"concept\":\"X\",\"value\":1000,\"expiry_at\":\"" + LocalDate.now() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("El encabezado X-User-Id es obligatorio"));
    }

    @Test
    void jobCancelsStalePendingOnlyWhenPseAnswers() throws Exception {
        start(body("{\"service_id\":10,\"value\":50000}")).andExpect(status().isCreated());
        var transaction = transactionRepository.findAll().get(0);
        transaction.setCreateAt(Instant.now().minus(25, ChronoUnit.HOURS));
        transactionRepository.save(transaction);

        when(pseClient.transactionInfo("CUS-1"))
                .thenThrow(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "PSE_REQUEST_TIMEOUT", "timeout"));
        job.run();
        assertTrue(TransactionStatus.PENDIENTE.is(transactionRepository.findAll().get(0).getStatus()));

        org.mockito.Mockito.reset(pseClient);
        when(pseClient.transactionInfo("CUS-1")).thenReturn(json("{\"transactionState\":\"PENDING\"}"));
        job.run();
        assertTrue(TransactionStatus.CANCELADA.is(transactionRepository.findAll().get(0).getStatus()));
    }

    @Test
    void searchAndStatuses() throws Exception {
        start(body("{\"service_id\":10,\"value\":50000}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/pse/transactions/search").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"status_id\",\"operator\":\"equals\",\"value\":1},"
                                + "{\"column\":\"code_trazability\",\"operator\":\"equals\",\"value\":\"CUS-1\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].details").doesNotExist());
        // Columnas documentadas en el README: subcolumnas de relaciones, mayúsculas indistintas, orden y errores.
        for (String filter : java.util.List.of(
                "{\"column\":\"status_id.status\",\"operator\":\"equals\",\"value\":\"PENDIENTE\"}",
                "{\"column\":\"status_id.id\",\"operator\":\"in\",\"value\":[1,2]}",
                "{\"column\":\"financial_institutionCode\",\"operator\":\"equals\",\"value\":null}",
                "{\"column\":\"urlGenerated\",\"operator\":\"equals\",\"value\":null}",
                "{\"column\":\"create_at\",\"operator\":\"equals\",\"value\":\"" + LocalDate.now() + "\"}",
                "{\"column\":\"create_at\",\"operator\":\"year\",\"value\":" + LocalDate.now().getYear() + "}",
                "{\"column\":\"create_by\",\"operator\":\"equals\",\"value\":119}",
                "{\"column\":\"FULL_NAME\",\"operator\":\"startsWith\",\"value\":\"cliente\"}",
                // El OR va en el segundo filtro del grupo: une ese filtro con el anterior.
                "{\"filters\":[{\"column\":\"identification\",\"operator\":\"equals\",\"value\":\"999\"},"
                        + "{\"column\":\"email\",\"operator\":\"contains\",\"value\":\"CLIENTE@\",\"logic\":\"OR\"}]}")) {
            mvc.perform(post("/api/v1/pse/transactions/search").param("sort_by", "transaction_value").param("sort_order", "asc")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"filters\":[" + filter + "]}"))
                    .andExpect(status().isOk())
                    .andExpect(result -> assertEquals(1, (Integer) JsonPath.read(result.getResponse().getContentAsString(), "$.data.total"), filter));
        }
        mvc.perform(post("/api/v1/pse/transactions/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"urlGenerated.concept\",\"operator\":\"contains\",\"value\":\"x\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(post("/api/v1/pse/transactions/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"password\",\"value\":\"x\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.message").value("Columna no permitida: password"));
        mvc.perform(get("/api/v1/pse/transactions").param("sort_by", "status_id"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/pse/urls/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"status_id.status\",\"operator\":\"equals\",\"value\":\"ACTIVA\"},"
                                + "{\"column\":\"expiry_at\",\"operator\":\"greaterThanOrEqual\",\"value\":\"2026-01-01\"}]}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/pse/transaction-statuses/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"status\",\"operator\":\"in\",\"value\":[\"APROBADA\",\"RECHAZADA\"]}]}"))
                .andExpect(jsonPath("$.data.total").value(2));
        mvc.perform(get("/api/v1/pse/transaction-statuses/select"))
                .andExpect(jsonPath("$.data.items.length()").value(5))
                .andExpect(jsonPath("$.data.items[1].label").value("APROBADA"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/pse/transaction-statuses/2").header("X-User-Id", USER))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/v1/pse/url-statuses/select"))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].label").value("ACTIVA"))
                .andExpect(jsonPath("$.data.items[1].label").value("PAGADA"))
                .andExpect(jsonPath("$.data.items[2].label").value("DESHABILITADA"));
        mvc.perform(post("/api/v1/pse/url-statuses/search").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filters\":[{\"column\":\"status\",\"operator\":\"startsWith\",\"value\":\"des\"}]}"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(3));
        mvc.perform(put("/api/v1/pse/url-statuses/1").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"otra\"}"))
                .andExpect(status().isConflict());
        String extra = mvc.perform(post("/api/v1/pse/url-statuses").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"en revision\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("EN REVISION"))
                .andReturn().getResponse().getContentAsString();
        Integer extraId = JsonPath.read(extra, "$.data.id");
        mvc.perform(post("/api/v1/pse/url-statuses").header("X-User-Id", USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"EN REVISION\"}"))
                .andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/pse/url-statuses/{id}", extraId).header("X-User-Id", USER))
                .andExpect(status().isOk());
    }

    private ResultActions start(String fields) throws Exception {
        String content = "{\"financial_institution_code\":\"1022\",\"bank_name\":\"Banco Test\",\"user_type\":0,"
                + "\"type_document\":\"CC\",\"identification\":\"123456\",\"full_name\":\"Cliente Prueba\","
                + "\"email\":\"cliente@test.co\",\"phone\":\"3000000000\",\"address\":\"Calle 1\","
                + "\"recaptcha_token\":\"ok\"," + fields + "}";
        return mvc.perform(post("/api/v1/pse/transactions").contentType(MediaType.APPLICATION_JSON).content(content));
    }

    private static String body(String... lines) {
        return "\"details\":[" + String.join(",", lines) + "]";
    }

    private String ticketHash(String response) {
        return JsonPath.read(response, "$.data.ticket_hash");
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
