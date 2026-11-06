# ms-pse

Microservicio de pagos PSE de Inttelgo. Sigue la estructura de `ms-build` y reemplaza lo de PSE en `backend_pipelines`: `php/pse-microservice.php` (cifrado y llamadas a PSE), `controllers/PSE.controller.js`, `controllers/GenerateURL.controller.js`, `controllers/Transaccion.controller.js` (consultas) y el worker `verificarTransaccionesPendientes.js`.

La autenticación no vive aquí. El proxy valida la sesión y reenvía `X-User-Id` en las escrituras administrativas (`create_by`, `update_by`). Los endpoints del landing son públicos.

Lo que no es de este dominio se consulta por HTTP:

| Dato | Servicio | Variable |
| --- | --- | --- |
| Cliente y estado de cada `service_id` | `ms-service` | `MS_SERVICE_URL` |
| Factura, cobros (`collection_build_id`), pagos y categorías de periodo | `ms-build` | `MS_BUILD_URL` |
| Pagos | PSE (ACH Colombia) | `PSE_*` |
| Validación anti-bots | Google reCAPTCHA | `RECAPTCHA_*` |

## Modelo

| Tabla | Qué guarda |
| --- | --- |
| `EstadoTransaccion` | Catálogo sembrado: `1 PENDIENTE`, `2 APROBADA`, `3 RECHAZADA`, `4 FALLIDA`, `5 CANCELADA`. El código usa esos ids. |
| `EstadoUrl` | Catálogo sembrado del estado de las URLs: `1 ACTIVA`, `2 PAGADA`, `3 DESHABILITADA`. El código usa esos ids. Vencida no es un estado: sale de `expiry_at`. |
| `GenerateUrl` | URL de pago con valor fijo. `status_id` apunta a `EstadoUrl`. |
| `Transaccion` | Transacción PSE. `code_trazability` es el CUS. `discount` es el % (1 a 100). `create_by` es siempre el usuario *Mi Recaudo PSE* (`PSE_SYSTEM_USER_ID`, hoy `119`). `build_id` es la factura creada en ms-build. `urlGenerated` apunta a la URL pagada. |
| `DetalleTransaccion` | Líneas de la transacción (`transaction_id`). Con `service_id` entra a la factura; con `service_id` + `extra_id` es un extra del servicio (p. ej. pantalla WIN+, catálogo `Extra` de ms-service); con `collection_build_id` paga una cuota del cobro y guarda el pago en `payment_id`. `value` es lo cobrado y `discount` los pesos que le quitó el %. |

`Transaccion.collection_build_id` se conserva por compatibilidad del esquema; los cobros van por línea.

## Flujo

1. **Inicio** (`POST /api/v1/pse/transactions`). Valida reCAPTCHA y las líneas, guarda la transacción `PENDIENTE` con su ticket (`Date.now()`, como hacía el landing) y la crea en PSE (`CreateTransactionPaymentNF`). Responde `pse_url` para redirigir al banco. Si PSE no la acepta, queda `FALLIDA`.
   - Con `url_generated` (el hash de `?payment=`) el valor es el de la URL y no se mandan líneas.
   - Sin URL, `transaction_value` es la suma de las líneas. El `discount` (% de 1 a 100) se aplica solo a las líneas con servicio, con el mismo cálculo que ms-build: resta el % y redondea hacia arriba a la centena. Así lo cobrado en PSE es igual a la factura. A ms-build se le manda el valor sin descuento y el %.
   - La descripción del pago (`paymentDescription`) la arma el servidor y no pasa de `PSE_DESCRIPTION_MAX_LENGTH` (80). Con facturas lleva el mes que se paga, la cantidad de servicios, de extras, el descuento y la cantidad de pagos adicionales, por ejemplo `Factura diciembre 2026 - 2 servicios - 1 extra - Descuento 10% ($12.000) - 1 pago adicional`. Si no cabe, quita los pesos del descuento y luego abrevia (`Fact dic 2026 - 2 serv - 1 extra - Dcto 10% - 1 pago adic`). Con URL de pago es el concepto de la URL.
   - Cada línea lleva `service_id` **o** `collection_build_id`. Los servicios deben existir, ser del mismo cliente y pagar el mismo periodo. Cada cobro debe tener cuotas pendientes.
   - Un extra es otra línea con el `service_id` y su `extra_id`; debe estar asociado a ese servicio en ms-service (`GET /api/v1/service/{id}/extras`). Si no trae `description` se usa el nombre del extra. El % de descuento no aplica a los extras.
2. **Retorno** (`GET /api/v1/pse/transactions/verify/{hash}`). PSE redirige a `PSE_ENTITY_URL + hash` (ticket cifrado). Se consulta el estado (`GetTransactionInformationNF`): `OK` → `APROBADA`, `NOT_AUTHORIZED` → `RECHAZADA`, `FAILED` → `FALLIDA`, `PENDING` sigue igual. Si PSE no responde, se devuelve el estado guardado.
3. **Aprobación**. Quien pasa la transacción a `APROBADA` la finaliza en PSE (`FinalizeTransactionPaymentNF`), marca la URL como pagada y concilia con ms-build:
   - Si hay líneas con `service_id` se crea **una factura** (cada extra va en su propia línea del detalle con `service_id` + `extra_id`) (`POST /api/v1/build/client/{clientId}`) sin caja, con `method_id`, `office_id` y `status_id` de las variables `PSE_BUILD_*` y el usuario `PSE_SYSTEM_USER_ID` (Mi Recaudo PSE). Su id queda en `build_id`.
   - Por cada línea con `collection_build_id` se registra el pago de la siguiente cuota (`POST /api/v1/build/collections/{id}/payments`) y su id queda en `payment_id`.
   - Cada paso que sale bien se guarda aunque falle el siguiente; lo que tiene id no se vuelve a crear.
4. **Job** (`PSE_PENDING_CRON`, cada 3 min). Consulta las pendientes en PSE; si PSE responde que sigue pendiente después de `PSE_PENDING_MAX_HOURS` la cancela (si PSE no responde, no la cancela). También reintenta en ms-build lo que quedó sin crear de las aprobadas de los últimos 30 días.

La consulta del landing y el job bloquean la fila (`SELECT … FOR UPDATE`) antes de cambiar el estado o crear en ms-build, así no se duplica la factura.

**Periodo de la factura.** `build_at` sale del estado actual de los servicios con las mismas categorías que valida ms-build (`/api/v1/build/movement-categories/{1,2,3}`): *Pago anticipado* → mes siguiente, *Pagos normales* → mes actual, *Mora* → mes anterior. Como la factura tiene un solo mes, una transacción no puede mezclar servicios de periodos distintos.

**Token PSE.** Se pide con client credentials (`PSE_URL + PSE_AUTH_PATH`) y se guarda en memoria hasta un minuto antes de vencer; si PSE responde 401 se pide otro y se reintenta una vez. El landing ya no maneja `pse_token`.

**Cifrado.** Igual que el SDK PHP: AES-256-GCM con `PSE_ENCRYPT_KEY`/`PSE_ENCRYPT_IV` → `base64(iv + cifrado + tag)`, envuelto en un JWE compacto `alg=dir, enc=A256GCM`. Los hashes de ticket y URL usan el formato de `encryptId` del Node (AES-256-CBC, llave SHA-256 de `PSE_HASH_SECRET`, `hex(iv):hex(dato)`); con el mismo secreto que `TOKEN_SECRET` siguen sirviendo los enlaces ya enviados.

## API

Base: `http://<host>:8009`. Todas las rutas (menos `/health`) están bajo `/api/v1/pse`.

### Convenciones

**Respuesta.** Todas las respuestas (menos `/health`) van envueltas:

```json
{ "success": true, "data": { … } }
{ "success": false, "error": { "code": "BAD_REQUEST", "message": "…" } }
```

**Headers.**

| Header | Cuándo |
| --- | --- |
| `Content-Type: application/json` | En toda petición con body |
| `X-User-Id: <id>` | Obligatorio en las escrituras administrativas (crear, editar y eliminar URLs y estados). Lo pone el proxy con el usuario de la sesión y queda en `create_by` / `update_by`. Si falta: `400` *"El encabezado X-User-Id es obligatorio"*. Las lecturas administrativas también pasan por el proxy con sesión, pero el servicio no exige el header. |

Los endpoints del landing no llevan sesión ni `X-User-Id`.

**Paginación** (query params de los `GET` de listas y de los `POST …/search`):

| Param | Tipo | Default | Regla |
| --- | --- | --- | --- |
| `skip` | entero | `0` | ≥ 0. Registros que se saltan |
| `limit` | entero | `100` | 1 a 1000 |
| `sort_by` | texto | `id` | Una columna con "Ordena: sí" en la tabla de columnas del recurso. Otra columna: `400` |
| `sort_order` | `asc` \| `desc` | `desc` en transacciones y URLs, `asc` en estados | Otro valor: `400` |

La respuesta paginada es:

```json
{ "success": true, "data": { "items": [ … ], "total": 57, "skip": 0, "limit": 100, "hasMore": false } }
```

**Filtros** (body de los `POST …/search`, opcional; sin body es igual al `GET` de la lista):

```json
{
  "filters": [
    { "column": "status_id", "operator": "equals", "value": 2 },
    { "column": "create_at", "operator": "greaterThanOrEqual", "value": "2026-11-01T05:00:00Z" },
    { "filters": [
        { "column": "identification", "operator": "contains", "value": "1010" },
        { "column": "full_name", "operator": "contains", "value": "perez", "logic": "OR" }
    ]}
  ]
}
```

Se lee: `status_id = 2 AND create_at >= 2026-11-01 AND (identification contiene "1010" OR full_name contiene "perez")`.

| Campo | Qué es |
| --- | --- |
| `column` | Columna permitida del recurso (ver las tablas de columnas de cada `/search`). No distingue mayúsculas. Una columna que no está en la tabla responde `400` *"Columna no permitida: …"* |
| `operator` | `contains` (default), `notContains`, `like`, `startsWith`, `endsWith` (texto, sin distinguir mayúsculas) · `equals`, `notEquals` (con `null` = es/no es nulo; en fechas `"yyyy-MM-dd"` compara solo el día) · `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual` · `in`, `notIn` (`value` es un arreglo) · `day`, `month`, `year` (parte de una fecha, `value` numérico) |
| `value` | Valor a comparar. Fechas y horas en ISO-8601 UTC (`2026-11-06T15:00:00Z`) o solo fecha (`2026-11-06`) |
| `logic` | `AND` (default) u `OR`. Va en cada filtro y dice cómo se une **con el filtro anterior** de su misma lista; en el primero de la lista no tiene efecto |
| `filters` | Grupo anidado (en vez de `column`): funciona como un paréntesis. Su `logic` dice cómo se une el grupo con el filtro anterior; dentro del grupo, cada filtro lleva el suyo |

Operadores según el tipo de la columna:

| Tipo | Operadores | `value` |
| --- | --- | --- |
| texto | `contains`, `notContains`, `like`, `startsWith`, `endsWith`, `equals`, `notEquals`, `in`, `notIn` | texto (arreglo en `in`/`notIn`) |
| entero / número | `equals`, `notEquals`, `greaterThan`, `greaterThanOrEqual`, `lessThan`, `lessThanOrEqual`, `in`, `notIn` | número |
| fecha y hora | lo de número + `day`, `month`, `year` | `"2026-11-06T15:00:00Z"`; con `equals`/`notEquals` también `"2026-11-06"` (todo ese día, en UTC); en `day`/`month`/`year` un número |
| fecha | lo de número + `day`, `month`, `year` | `"2026-11-06"` |
| relación | `equals`, `notEquals`, `in`, `notIn` | id de la relación, o `null` |

`equals`/`notEquals` con `"value": null` buscan nulo / no nulo.


Una columna u operador no permitido responde `400`.

**Errores comunes.**

| HTTP | `code` | Cuándo |
| --- | --- | --- |
| 400 | `BAD_REQUEST` | Validación del body o de los params. El mensaje lista `campo: motivo; …` con el nombre del campo en Java (p. ej. `financialInstitutionCode`) |
| 401 | `INVALID_RECAPTCHA` | reCAPTCHA inválido o vencido |
| 404 | `NOT_FOUND` | Registro o ruta inexistente |
| 405 | `METHOD_NOT_ALLOWED` | Método no soportado en la ruta |
| 409 | `CONFLICT` | Regla de negocio (URL ya pagada, estado en uso, …) |
| 500 | `ERROR` | Error no controlado |
| 502 | `PSE_ERROR`, `PSE_TOKEN_EXPIRED`, `PSE_OAUTH_UNAUTHORIZED`, `PSE_OAUTH_HTTP_<n>`, `PSE_TOKEN_ERROR`, `PSE_EMPTY_RESPONSE`, `PSE_DECRYPT_ERROR`, `PSE_PARSE_ERROR`, `PSE_TLS_ERROR`, `PSE_CONNECTION_ERROR`, `PSE_TRANSACTION_REJECTED` | PSE respondió mal o no aceptó la transacción |
| 502 | `BUILD_ERROR`, `BUILD_UNAVAILABLE`, `SERVICE_UNAVAILABLE`, `RECAPTCHA_UNAVAILABLE` | Falló ms-build, ms-service o Google |
| 503 | `PSE_CONNECTION_REFUSED`, `PSE_DNS_ERROR` | No hay conexión con PSE |
| 504 | `PSE_CONNECTION_TIMEOUT`, `PSE_REQUEST_TIMEOUT` | PSE no respondió a tiempo (`PSE_TIMEOUT_SECONDS`) |

---

### PseController — flujo de pago del landing (públicos)

#### `GET /api/v1/pse/banks`

Lista de bancos de PSE (`GetBankListNF`).

- **Params:** ninguno.
- **Body:** ninguno.
- **Respuesta `200`:** `data.banks` es la lista tal cual la devuelve PSE.

```json
{ "success": true, "data": { "banks": [
  { "financialInstitutionCode": "0", "financialInstitutionName": "A continuación seleccione su banco" },
  { "financialInstitutionCode": "1022", "financialInstitutionName": "BANCO UNION COLOMBIANO" }
] } }
```

- **Errores:** los `502/503/504` de PSE.

#### `POST /api/v1/pse/transactions`

Inicia un pago: valida, guarda la transacción `PENDIENTE` y la crea en PSE. El landing debe redirigir al usuario a `pse_url`.

- **Params:** ninguno.
- **Body:**

| Campo | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `financial_institution_code` | texto | sí | Código del banco elegido (de `/banks`). Máx. 20 |
| `bank_name` | texto | no | Nombre del banco. Máx. 255 |
| `user_type` | entero | sí | `0` persona natural, `1` jurídica |
| `type_document` | texto | sí | Tipo de documento según PSE (`CC`, `CE`, `NIT`, `TI`, `PP`, …). Máx. 10 |
| `identification` | texto | sí | Número de documento. Máx. 30 |
| `full_name` | texto | sí | Máx. 255 |
| `email` | texto | sí | Correo válido. Máx. 255 |
| `phone` | texto | sí | Máx. 30 |
| `address` | texto | sí | Máx. 255 |
| `discount` | entero | no | Porcentaje 1 a 100. Solo con líneas de servicio (mensualidad); no con URL |
| `url_generated` | texto | no | Hash de la URL de pago (`?payment=` del enlace). Si va, no se mandan `details` ni `discount` |
| `recaptcha_token` | texto | sí, si `RECAPTCHA_ENABLED=true` | Token de reCAPTCHA del landing |
| `details` | arreglo | sí, si no va `url_generated` | Líneas a pagar (abajo). Al menos una |

Cada línea de `details`:

| Campo | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `service_id` | entero | uno de los dos | Servicio (ms-service). Sin `extra_id` es la mensualidad: va a la factura |
| `collection_build_id` | entero | uno de los dos | Cobro de ms-build: paga su siguiente cuota |
| `extra_id` | entero | no | Extra del servicio (ms-service, p. ej. pantalla WIN+). Va **con** `service_id`, en una línea aparte de la mensualidad |
| `value` | número | sí | > 0. Valor **sin** descuento |
| `description` | texto | no | Máx. 255. En extras, si no va, se usa el nombre del extra |
| `internet_value` | número | no | ≥ 0 |
| `telephony_value` | número | no | ≥ 0 |
| `television_value` | número | no | ≥ 0 |

Reglas de las líneas:

- Cada línea lleva `service_id` o `collection_build_id`, no los dos. `extra_id` sin `service_id` es error.
- Una mensualidad por servicio, cada extra una vez por servicio y cada cobro una vez.
- Los servicios deben existir en ms-service, ser del mismo cliente y pagar el mismo periodo (anticipado, actual o mora).
- El extra debe estar asociado al servicio en ms-service.
- El cobro debe tener cuotas pendientes.
- El `discount` se aplica solo a las mensualidades: resta el % y redondea hacia arriba a la centena (como ms-build). El total a pagar debe quedar > 0.

Ejemplo (pago de factura):

```json
{
  "financial_institution_code": "1022",
  "bank_name": "BANCO UNION COLOMBIANO",
  "user_type": 0,
  "type_document": "CC",
  "identification": "1010961040",
  "full_name": "Juan Perez",
  "email": "juan@correo.co",
  "phone": "3001234567",
  "address": "Calle 1 # 2-3",
  "discount": 10,
  "recaptcha_token": "03AFcWeA…",
  "details": [
    { "description": "Mensualidad 300MB", "service_id": 10, "internet_value": 60000, "value": 60000 },
    { "service_id": 10, "extra_id": 5, "value": 15000 },
    { "description": "Cuota modem", "collection_build_id": 30, "value": 20000 }
  ]
}
```

Se cobran 54.000 (60.000 − 10%) + 15.000 (extra, sin descuento) + 20.000 (cuota) = 89.000.

Ejemplo (pago de una URL):

```json
{
  "financial_institution_code": "1022",
  "user_type": 0,
  "type_document": "CC",
  "identification": "1010961040",
  "full_name": "Juan Perez",
  "email": "juan@correo.co",
  "phone": "3001234567",
  "address": "Calle 1 # 2-3",
  "url_generated": "9f1c…:4ab2…",
  "recaptcha_token": "03AFcWeA…"
}
```

- **Respuesta `201`:**

```json
{ "success": true, "data": {
  "number_ticket": "1762444800123",
  "ticket_hash": "e51b7159e2db2fa80af0ec005b5d22cf:007249958ad490a63c9d5c4be0e2e2ab",
  "code_trazability": "3416512",
  "pse_url": "https://registro.pse.com.co/PSENF/index.html?enc=…",
  "transaction_cycle": "1",
  "return_code": "SUCCESS"
} }
```

| Campo | Qué es |
| --- | --- |
| `number_ticket` | Ticket de la transacción (`ticketId` en PSE) |
| `ticket_hash` | Ticket cifrado: es lo que PSE agrega a `PSE_ENTITY_URL` al volver y lo que recibe `/transactions/verify/{hash}` |
| `code_trazability` | CUS de PSE |
| `pse_url` | URL del banco a la que se redirige |

- **Errores:** `400` validación o reglas de líneas, `400` *"URL canjeada o superó el plazo"*, `401 INVALID_RECAPTCHA`, `404` servicio, cobro o URL inexistente, `502 PSE_TRANSACTION_REJECTED` (PSE no la aceptó; la transacción queda `FALLIDA`), y los `502/503/504` de PSE, ms-service y ms-build.

#### `GET /api/v1/pse/transactions/verify/{hash}`

Consulta el estado al volver de PSE. Si está `PENDIENTE` pregunta a PSE (`GetTransactionInformationNF`) y, si quedó aprobada, la finaliza y crea la factura y los pagos en ms-build. Si PSE no responde, devuelve el estado guardado (el job vuelve a consultar).

- **Params (ruta):**

| Param | Tipo | Qué es |
| --- | --- | --- |
| `hash` | texto | `ticket_hash` del inicio (el que trae la URL de retorno) |

- **Body:** ninguno.
- **Respuesta `200`:** la transacción con sus líneas (ver [Transacción](#objeto-transacción)). Mirar `status.status`: `PENDIENTE`, `APROBADA`, `RECHAZADA`, `FALLIDA` o `CANCELADA`.
- **Errores:** `400` *"Enlace inválido"*, `404` *"Transacción no encontrada"*.

#### `GET /api/v1/pse/urls/public/{hash}`

Lo que muestra el landing al abrir un enlace de pago (`/pse?payment=<hash>`).

- **Params (ruta):**

| Param | Tipo | Qué es |
| --- | --- | --- |
| `hash` | texto | Valor de `payment` del enlace |

- **Body:** ninguno.
- **Respuesta `200`:**

```json
{ "success": true, "data": {
  "payment": "9f1c…:4ab2…",
  "concept": "Traslado de servicio",
  "value": 35000.00,
  "expiry_at": "2026-11-30"
} }
```

`payment` se manda como `url_generated` al iniciar el pago.

- **Errores:** `400` *"Enlace inválido"*, `404` *"URL no válida"* (no existe o fue eliminada), `400` *"URL canjeada o superó el plazo"* (pagada, deshabilitada o vencida).

---

### TransaccionController — consulta administrativa (`/api/v1/pse/transactions`)

#### `GET /api/v1/pse/transactions`

Lista paginada de transacciones, sin líneas.

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `desc`). Ver [Paginación](#convenciones).
- **Body:** ninguno.
- **Respuesta `200`:** página de [Transacción](#objeto-transacción) sin `details`.

#### `POST /api/v1/pse/transactions/search`

Igual que la lista, con filtros.

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `desc`).
- **Body (opcional):** `{ "filters": [ … ] }` (ver [Filtros](#convenciones)).

Columnas permitidas (para `column` y `sort_by`):

| Columna | Tipo | Ordena | Qué es |
| --- | --- | --- | --- |
| `id` | entero | sí | Id de la transacción |
| `number_ticket` | texto | sí | Ticket (`ticketId` en PSE) |
| `code_trazability` | texto | sí | CUS de PSE |
| `transaction_value` | número | sí | Valor cobrado |
| `discount` | entero | sí | % de descuento (1–100) |
| `iva_value` | número | sí | IVA (siempre 0) |
| `bank_code` | texto | sí | Código del banco elegido |
| `bank_name` | texto | sí | Nombre del banco |
| `type_document` | texto | sí | Tipo de documento del pagador |
| `identification` | texto | sí | Documento del pagador |
| `full_name` | texto | sí | Nombre del pagador |
| `email` | texto | sí | Correo del pagador |
| `phone` | texto | sí | Teléfono del pagador |
| `address` | texto | sí | Dirección del pagador |
| `payment_description` | texto | sí | Descripción enviada a PSE |
| `first_reference` | texto | sí | Referencia 1 (teléfono) |
| `second_reference` | texto | sí | Referencia 2 (documento) |
| `thirth_reference` | texto | sí | Referencia 3 (ticket) |
| `entitycode` | texto | sí | Código de la entidad en PSE |
| `financial_institutionCode` | texto | sí | Banco que reportó PSE |
| `service_code` | texto | sí | Código de servicio en PSE |
| `trasnsaction_cycle` | texto | sí | Ciclo de la transacción en PSE |
| `create_at` | fecha y hora | sí | Inicio del pago |
| `create_by` | entero | sí | Usuario Mi Recaudo PSE |
| `response_at` | fecha y hora | sí | Cuando PSE dio el estado final |
| `update_at` | fecha y hora | sí | Última actualización |
| `bank_process_date` | fecha y hora | sí | Fecha de proceso del banco |
| `build_id` | entero | sí | Factura creada en ms-build (`null` = sin factura) |
| `collection_build_id` | entero | sí | Compatibilidad del esquema (los cobros van por línea) |
| `status_id` | relación (estado) | no | Id del estado: `1` PENDIENTE, `2` APROBADA, `3` RECHAZADA, `4` FALLIDA, `5` CANCELADA |
| `status_id.id` | entero | no | Igual que `status_id` |
| `status_id.status` | texto | no | Nombre del estado (`"APROBADA"`) |
| `urlGenerated` | relación (URL) | no | Id de la URL pagada (`null` = pago de factura) |
| `urlGenerated.id` | entero | no | Igual que `urlGenerated` |
| `urlGenerated.concept` | texto | no | Concepto de la URL pagada |
| `urlGenerated.value` | número | no | Valor de la URL pagada |

Las líneas (`details`) no se filtran desde aquí.

Ejemplo: aprobadas de noviembre de un documento, con factura creada.

```json
{ "filters": [
  { "column": "status_id", "operator": "equals", "value": 2 },
  { "column": "create_at", "operator": "month", "value": 11 },
  { "column": "identification", "operator": "equals", "value": "1010961040" },
  { "column": "build_id", "operator": "notEquals", "value": null }
] }
```

Ejemplo: pagos de URL cuyo concepto tenga "traslado", o pagos rechazados/fallidos.

```json
{ "filters": [
  { "column": "urlGenerated.concept", "operator": "contains", "value": "traslado" },
  { "column": "status_id", "operator": "in", "value": [3, 4], "logic": "OR" }
] }
```

- **Respuesta `200`:** página de [Transacción](#objeto-transacción) sin `details`.

#### `GET /api/v1/pse/transactions/{id}`

- **Params (ruta):** `id` (entero) de la transacción.
- **Body:** ninguno.
- **Respuesta `200`:** [Transacción](#objeto-transacción) con `details`.
- **Errores:** `404` *"La transacción no existe"*.

#### `POST /api/v1/pse/transactions/{id}/verify`

Consulta ya el estado en PSE (sin esperar al job) y, si quedó aprobada, crea lo que falte en ms-build.

- **Params (ruta):** `id` (entero).
- **Body:** ninguno.
- **Respuesta `200`:** [Transacción](#objeto-transacción) con `details`.
- **Errores:** `404`, y aquí sí se devuelven los `502/503/504` de PSE.

#### `POST /api/v1/pse/transactions/{id}/settle`

Reintenta crear en ms-build la factura o los pagos que falten de una transacción `APROBADA`. Lo que ya tiene id no se vuelve a crear.

- **Params (ruta):** `id` (entero).
- **Body:** ninguno.
- **Respuesta `200`:**

```json
{ "success": true, "data": { "build_id": 77, "errors": [] } }
```

`errors` dice qué no se pudo crear (p. ej. `"Cobro 30: ms-build no pudo registrar el pago del cobro 30: …"`); vacío si todo quedó. Si la transacción no está aprobada: `errors: ["La transacción no está aprobada"]`.

- **Errores:** `404`.

#### Objeto transacción

```json
{
  "id": 12,
  "number_ticket": "1762444800123",
  "code_trazability": "3416512",
  "transaction_value": 89000.00,
  "discount": 10,
  "iva_value": 0.00,
  "bank_code": "1022",
  "bank_name": "BANCO UNION COLOMBIANO",
  "type_document": "CC",
  "identification": "1010961040",
  "full_name": "Juan Perez",
  "email": "juan@correo.co",
  "phone": "3001234567",
  "create_at": "2026-11-06T15:20:00Z",
  "create_by": 119,
  "response_at": "2026-11-06T15:24:10Z",
  "update_at": "2026-11-06T15:24:10Z",
  "first_reference": "3001234567",
  "second_reference": "1010961040",
  "thirth_reference": "1762444800123",
  "entitycode": "901132952",
  "financial_institutionCode": "1022",
  "service_code": "1001",
  "bank_process_date": "2026-11-06T15:23:00Z",
  "trasnsaction_cycle": "2",
  "payment_description": "Factura diciembre 2026 - 1 servicio - 1 extra - Descuento 10% - 1 pago adicional",
  "address": "Calle 1 # 2-3",
  "urlGenerated": null,
  "status_id": 2,
  "status": { "id": 2, "status": "APROBADA" },
  "build_id": 77,
  "collection_build_id": null,
  "details": [
    { "id": 30, "description": "Mensualidad 300MB", "internet_value": 60000.00, "telephony_value": null, "television_value": null,
      "value": 54000.00, "discount": 6000.00, "service_id": 10, "extra_id": null, "payment_id": null, "collection_build_id": null },
    { "id": 31, "description": "Pantalla WIN+", "internet_value": null, "telephony_value": null, "television_value": null,
      "value": 15000.00, "discount": null, "service_id": 10, "extra_id": 5, "payment_id": null, "collection_build_id": null },
    { "id": 32, "description": "Cuota modem", "internet_value": null, "telephony_value": null, "television_value": null,
      "value": 20000.00, "discount": null, "service_id": null, "extra_id": null, "payment_id": 88, "collection_build_id": 30 }
  ]
}
```

- `discount` de la transacción es el %; `discount` de la línea son los pesos que le quitó ese %; `value` de la línea es lo cobrado.
- `create_by` es siempre el usuario *Mi Recaudo PSE* (`PSE_SYSTEM_USER_ID`).
- `build_id` es la factura en ms-build y `payment_id` el pago de la cuota; se llenan al aprobarse.
- `details` solo viene en el detalle (`/{id}`, `/{id}/verify`, `/verify/{hash}`), no en las listas.

---

### GenerateUrlController — URLs de pago (`/api/v1/pse/urls`)

El estado sale de su propio catálogo, `EstadoUrl` (ver [EstadoUrlController](#estadourlcontroller--estados-de-url-apiv1pseurl-statuses)): `1 ACTIVA` = se puede pagar mientras no venza, `2 PAGADA` = la pone el sistema al aprobarse el pago, `3 DESHABILITADA` = desactivada a mano.

#### `GET /api/v1/pse/urls`

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `desc`).
- **Body:** ninguno.
- **Respuesta `200`:** página de [URL](#objeto-url). No incluye las eliminadas.

#### `POST /api/v1/pse/urls/search`

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `desc`).
- **Body (opcional):** `{ "filters": [ … ] }`. Las eliminadas nunca salen.

Columnas permitidas (para `column` y `sort_by`):

| Columna | Tipo | Ordena | Qué es |
| --- | --- | --- | --- |
| `id` | entero | sí | Id de la URL |
| `url` | texto | sí | Enlace del landing |
| `concept` | texto | sí | Concepto |
| `value` | número | sí | Valor a pagar |
| `expiry_at` | fecha | sí | Último día para pagar |
| `create_at` | fecha y hora | sí | Creación |
| `create_by` | entero | sí | Usuario que la creó |
| `update_at` | fecha y hora | sí | Última edición |
| `update_by` | entero | sí | Usuario de la última edición |
| `status_id` | relación (estado de URL) | no | `1` ACTIVA, `2` PAGADA, `3` DESHABILITADA |
| `status_id.id` | entero | no | Igual que `status_id` |
| `status_id.status` | texto | no | `"ACTIVA"`, `"PAGADA"`, `"DESHABILITADA"` |

Ejemplo: activas que no han vencido.

```json
{ "filters": [
  { "column": "status_id", "operator": "equals", "value": 1 },
  { "column": "expiry_at", "operator": "greaterThanOrEqual", "value": "2026-11-06" }
] }
```

- **Respuesta `200`:** página de [URL](#objeto-url).

#### `GET /api/v1/pse/urls/{id}`

- **Params (ruta):** `id` (entero).
- **Body:** ninguno.
- **Respuesta `200`:** [URL](#objeto-url).
- **Errores:** `404` *"URL no encontrada"*.

#### `POST /api/v1/pse/urls`

Crea la URL activa y arma el enlace del landing (`LANDINGPAGE_URL/pse?payment=<hash>`).

- **Headers:** `X-User-Id`.
- **Params:** ninguno.
- **Body:**

| Campo | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `concept` | texto | sí | Máx. 255. Es la descripción del pago en PSE (recortada a `PSE_DESCRIPTION_MAX_LENGTH`) |
| `value` | número | sí | > 0 |
| `expiry_at` | fecha `yyyy-MM-dd` | sí | Último día en que se puede pagar (incluido). No anterior a hoy |

```json
{ "concept": "Traslado de servicio", "value": 35000, "expiry_at": "2026-11-30" }
```

- **Respuesta `201`:** [URL](#objeto-url).
- **Errores:** `400` validación o fecha pasada, `400` sin `X-User-Id`.

#### `PUT /api/v1/pse/urls/{id}`

Edita una URL. Los campos que no se mandan no cambian.

- **Headers:** `X-User-Id`.
- **Params (ruta):** `id` (entero).
- **Body:**

| Campo | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `concept` | texto | no | No vacío. Máx. 255 |
| `value` | número | no | > 0 |
| `expiry_at` | fecha `yyyy-MM-dd` | no | Nueva fecha de vencimiento |
| `status_id` | entero | no | `1` activar o `3` deshabilitar. `2` PAGADA solo la pone el sistema. Otro valor: `400` |

```json
{ "expiry_at": "2026-12-15", "status_id": 1 }
```

- **Respuesta `200`:** [URL](#objeto-url).
- **Errores:** `404`, `400` validación, `409` *"La URL ya fue pagada y no se puede modificar"*.

#### `DELETE /api/v1/pse/urls/{id}`

Baja lógica (`delete_at`).

- **Headers:** `X-User-Id`.
- **Params (ruta):** `id` (entero).
- **Body:** ninguno.
- **Respuesta `200`:** `{ "success": true, "data": { "message": "URL eliminada exitosamente" } }`
- **Errores:** `404`, `409` *"La URL ya fue pagada y no se puede eliminar"*.

#### Objeto URL

```json
{
  "id": 4,
  "url": "https://pagos.inttelgo.com/pse?payment=9f1c…:4ab2…",
  "concept": "Traslado de servicio",
  "value": 35000.00,
  "expiry_at": "2026-11-30",
  "status_id": 1,
  "status": { "id": 1, "status": "ACTIVA" },
  "expired": false,
  "create_at": "2026-11-06T15:00:00Z",
  "create_by": 7,
  "update_at": "2026-11-06T15:00:00Z",
  "update_by": 7
}
```

`expired` es `true` cuando `expiry_at` ya pasó.

---

### EstadoTransaccionController — estados (`/api/v1/pse/transaction-statuses`)

Los estados `1` a `5` son del sistema: no se pueden editar ni eliminar.

#### `GET /api/v1/pse/transaction-statuses/select`

- **Params:** ninguno.
- **Body:** ninguno.
- **Respuesta `200`:**

```json
{ "success": true, "data": { "items": [
  { "value": 1, "label": "PENDIENTE" }, { "value": 2, "label": "APROBADA" }, { "value": 3, "label": "RECHAZADA" },
  { "value": 4, "label": "FALLIDA" }, { "value": 5, "label": "CANCELADA" }
] } }
```

#### `GET /api/v1/pse/transaction-statuses`

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `asc`).
- **Body:** ninguno.
- **Respuesta `200`:** página de [Estado](#objeto-estado).

#### `POST /api/v1/pse/transaction-statuses/search`

- **Params (query):** `skip`, `limit`, `sort_by`, `sort_order` (default `asc`).
- **Body (opcional):** `{ "filters": [ … ] }`. Los eliminados nunca salen.

Columnas permitidas (para `column` y `sort_by`):

| Columna | Tipo | Ordena | Qué es |
| --- | --- | --- | --- |
| `id` | entero | sí | Id del estado |
| `status` | texto | sí | Nombre (`"APROBADA"`) |
| `create_at` | fecha y hora | sí | Creación |
| `create_by` | entero | sí | Usuario que lo creó |
| `update_at` | fecha y hora | sí | Última edición |
| `update_by` | entero | sí | Usuario de la última edición |

```json
{ "filters": [ { "column": "status", "operator": "in", "value": ["APROBADA", "RECHAZADA"] } ] }
```
- **Respuesta `200`:** página de [Estado](#objeto-estado).

#### `GET /api/v1/pse/transaction-statuses/{id}`

- **Params (ruta):** `id` (entero).
- **Respuesta `200`:** [Estado](#objeto-estado). **Errores:** `404`.

#### `POST /api/v1/pse/transaction-statuses`

- **Headers:** `X-User-Id`.
- **Body:**

| Campo | Tipo | Obligatorio | Regla |
| --- | --- | --- | --- |
| `status` | texto | sí | Máx. 50. Se guarda en mayúsculas; no puede repetirse |

```json
{ "status": "REVERSADA" }
```

- **Respuesta `201`:** [Estado](#objeto-estado). **Errores:** `400`, `409` *"El estado de transacción ya existe"*.

#### `PUT /api/v1/pse/transaction-statuses/{id}`

- **Headers:** `X-User-Id`.
- **Params (ruta):** `id` (entero).
- **Body:** igual que crear (`status`).
- **Respuesta `200`:** [Estado](#objeto-estado). **Errores:** `404`, `409` si es un estado del sistema o el nombre ya existe.

#### `DELETE /api/v1/pse/transaction-statuses/{id}`

Baja lógica.

- **Headers:** `X-User-Id`.
- **Params (ruta):** `id` (entero).
- **Respuesta `200`:** `{ "success": true, "data": { "message": "Estado de transacción eliminado exitosamente" } }`
- **Errores:** `404`, `409` si es del sistema o lo usa alguna transacción.

#### Objeto estado

```json
{ "id": 6, "status": "REVERSADA", "create_at": "2026-11-06T15:00:00Z", "create_by": 7, "update_at": "2026-11-06T15:00:00Z", "update_by": 7 }
```

---

### EstadoUrlController — estados de URL (`/api/v1/pse/url-statuses`)

Catálogo de estados de las URLs de pago, separado del de transacciones. Mismas reglas y mismo [objeto estado](#objeto-estado). Los estados `1` a `3` son del sistema: no se pueden editar ni eliminar.

| Petición | Params | Body | Respuesta / errores |
| --- | --- | --- | --- |
| `GET /api/v1/pse/url-statuses/select` | ninguno | ninguno | `200` `{ "items": [ { "value": 1, "label": "ACTIVA" }, { "value": 2, "label": "PAGADA" }, { "value": 3, "label": "DESHABILITADA" } ] }` |
| `GET /api/v1/pse/url-statuses` | query: `skip`, `limit`, `sort_by`, `sort_order` (default `asc`) | ninguno | `200` página de estados |
| `POST /api/v1/pse/url-statuses/search` | query: igual que el listado | opcional `{ "filters": [ … ] }` | `200` página de estados |
| `GET /api/v1/pse/url-statuses/{id}` | ruta: `id` | ninguno | `200` estado; `404` |
| `POST /api/v1/pse/url-statuses` | header `X-User-Id` | `{ "status": "EN REVISION" }` (obligatorio, máx. 50, se guarda en mayúsculas, no se repite) | `201` estado; `400`; `409` *"El estado de URL ya existe"* |
| `PUT /api/v1/pse/url-statuses/{id}` | header `X-User-Id`; ruta: `id` | igual que crear | `200` estado; `404`; `409` si es del sistema o el nombre ya existe |
| `DELETE /api/v1/pse/url-statuses/{id}` | header `X-User-Id`; ruta: `id` | ninguno | `200` *"Estado de URL eliminado exitosamente"*; `404`; `409` si es del sistema o lo usa alguna URL |

Columnas del search: las mismas que en estados de transacción (`id`, `status`, `create_at`, `create_by`, `update_at`, `update_by`; todas ordenan).

```json
{ "filters": [ { "column": "status", "operator": "startsWith", "value": "des" } ] }
```

---

### HealthController

#### `GET /health`

- **Params / Body:** ninguno.
- **Respuesta `200`** (sin envoltura): `{ "status": "healthy", "service": "ms-pse" }`

## Variables de entorno

Ver `.env.example`. Las de PSE no tienen valor por defecto: sin ellas el servicio arranca pero los pagos fallan con un error de configuración.

| Variable | Uso |
| --- | --- |
| `PSE_URL`, `PSE_AUTH_PATH`, `PSE_VERSION`, `PSE_API_KEY` | API y OAuth de PSE |
| `PSE_CLIENT_ID`, `PSE_CLIENT_SECRET` | Credenciales OAuth |
| `PSE_ENCRYPT_KEY` (32 car.), `PSE_ENCRYPT_IV` (16 car.) | Cifrado de los mensajes |
| `PSE_ENTITY_CODE`, `PSE_SERVICE_CODE` | Comercio en PSE |
| `PSE_ENTITY_URL` | URL de retorno; se le concatena el ticket cifrado |
| `PSE_BENEFICIARY_*` | Datos del beneficiario |
| `PSE_HASH_SECRET` | Secreto de los hashes (mismo valor que `TOKEN_SECRET` del Node) |
| `LANDINGPAGE_URL` | Base de las URLs de pago (`/pse?payment=…`) |
| `PSE_DESCRIPTION_MAX_LENGTH` | Máximo de caracteres de la descripción en PSE (80) |
| `PSE_SYSTEM_USER_ID` | Usuario *Mi Recaudo PSE* (`119`): `create_by` de la transacción y `X-User-Id` de la factura y los pagos en ms-build |
| `PSE_BUILD_METHOD_ID`, `PSE_BUILD_OFFICE_ID`, `PSE_BUILD_STATUS_ID`, `PSE_ELECTRONIC_BUILD` | Factura y pagos en ms-build |
| `PSE_PENDING_JOB`, `PSE_PENDING_CRON`, `PSE_PENDING_MAX_HOURS` | Job de pendientes |
| `RECAPTCHA_ENABLED`, `RECAPTCHA_SECRET_KEY`, `RECAPTCHA_VERIFY_URL` | reCAPTCHA |
| `MS_BUILD_URL`, `MS_SERVICE_URL` | Otros microservicios |
| `MYSQL_*`, `PORT` (8009), `TIMEZONE` | Base de datos y servidor |

## Arranque

```bash
cp .env.example .env   # completar
./mvnw spring-boot:run
# o
docker compose up --build
```

Pruebas: `./mvnw test`. `CryptoCompatibilityTest` descifra un JWE generado con el SDK PHP original y un hash generado con `encryptId` del Node.

## Qué queda afuera

PDF del comprobante de pago (`/pse/pdf/:id`, `/transaccion/:id/pdf`), Excel general de transacciones, `cuentasCortePSE` (es de cuentas, no de PSE) y las tablas viejas `TransaccionPagoCuenta`/`TransaccionPagoAdicional`/`Cupon` (reemplazadas por `DetalleTransaccion`).

Requiere el cambio en ms-build que hace opcional `box_id` al crear la factura.

Si ya existe una base creada con una versión anterior de ms-pse (cuando `GenerateUrl.status_id` apuntaba a `EstadoTransaccion`), `ddl-auto: update` crea y siembra `EstadoUrl` pero no cambia la llave foránea vieja. Antes de arrancar:

```sql
-- nombre de la FK vieja: SHOW CREATE TABLE GenerateUrl;
ALTER TABLE GenerateUrl DROP FOREIGN KEY <fk_vieja>;
UPDATE GenerateUrl SET status_id = CASE status_id WHEN 1 THEN 1 WHEN 2 THEN 2 ELSE 3 END;
```
