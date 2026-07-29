# SPEC_PROCESS — whatsapp-process-function

Especificación funcional y técnica para implementar este proyecto desde cero. Azure Function
(Java) que actúa como **dispatcher**: consume los eventos de WhatsApp ya validados que
`whatsapp-webhook-function` (Function 1, `fnctdemolab01`) dejó en una Azure Storage Queue, y los
reenvía a dos endpoints de Adobe (CDP Audience, AJO Webhook), respetando un límite de 30 TPS por
endpoint.

Documenta la arquitectura, contratos, configuración y pasos necesarios para implementar y
desplegar este proyecto sobre la infraestructura ya provisionada (`fnctdemolab02`, ver
`doc/guia-poc-subnet-nsg-webhook-whatsapp.md` del proyecto hermano `whatsapp-webhook-function`).

---

## 1. Objetivo y alcance

- **Qué hace**: se dispara por un evento nuevo en la Storage Queue `demolab-queue`
  (`stgdemolabv2`), y por cada mensaje reenvía el payload a dos endpoints de Adobe — CDP Audience
  (streaming ingestion) y AJO Webhook — aplicando un rate limit de 30 TPS independiente por
  endpoint.
- **Qué NO hace**: no recibe tráfico HTTP entrante, no valida firmas (eso ya lo hizo Function 1),
  no decide lógica de negocio de audiencias/journeys — solo transporta el evento hacia Adobe con
  control de tasa y reintentos.
- **Runtime**: Azure Functions, Java 21, Linux, **Queue Trigger** (sin HTTP Trigger), desplegado en
  la Function App ya existente `fnctdemolab02`.

---

## 2. Contrato del trigger

### 2.1 Entrada — Queue Trigger sobre `demolab-queue`

```java
@FunctionName("WhatsAppProcess")
public void run(
        @QueueTrigger(name = "message",
                      queueName = "%QUEUE_NAME%",
                      connection = "QueueStorage")
        String message,
        ExecutionContext context)
```

- `connection = "QueueStorage"` — **nunca `AzureWebJobsStorage`** (esa apunta a `stgdemolabfnct2`,
  el storage de runtime del host, no al de negocio `stgdemolabv2`; este fue exactamente el bug que
  se corrigió en Function 1 — ver `doc/guia-poc-subnet-nsg-webhook-whatsapp.md` sección 4.1 del
  proyecto hermano).
- `message` es el JSON crudo de WhatsApp tal cual lo dejó Function 1 (sin transformar,
  sin re-serializar).
- Los mensajes que fallan van a una poison queue propia (`demolab-process-poison-queue`, ver §5) —
  la Function captura el fallo y completa el mensaje original en el primer intento; no depende del
  dead-letter nativo (`maxDequeueCount`).

### 2.2 Salida — POST a dos endpoints Adobe

Por cada mensaje, dos llamadas HTTP `POST` independientes:

| Endpoint | Config URL | Config API key (Key Vault) | Rate limit |
|---|---|---|---|
| CDP Audience | `ADOBE_CDP_AUDIENCE_ENDPOINT` | `AdobeCdpAudienceApiKey` | `ADOBE_CDP_AUDIENCE_RATE_LIMIT_TPS` (default 30) |
| AJO Webhook | `ADOBE_AJO_WEBHOOK_ENDPOINT` | `AdobeAjoWebhookApiKey` | `ADOBE_AJO_WEBHOOK_RATE_LIMIT_TPS` (default 30) |

**Lógica por mensaje:**

```
1. Adquirir permiso del rate limiter de CDP (bloqueante, espera si se supera el TPS) → POST a
   ADOBE_CDP_AUDIENCE_ENDPOINT con header Authorization/API key = AdobeCdpAudienceApiKey.
2. Adquirir permiso del rate limiter de AJO (independiente del de CDP) → POST a
   ADOBE_AJO_WEBHOOK_ENDPOINT con header Authorization/API key = AdobeAjoWebhookApiKey.
3. Cada envío se reintenta independientemente ante fallas transitorias (ver §5).
4. Si, tras agotar reintentos, CUALQUIERA de los dos envíos falla → la Function captura la
   excepción y escribe el mensaje en la poison queue propia (`demolab-process-poison-queue`); el
   mensaje original se completa (se remueve de `demolab-queue`) en ese mismo intento — no hay
   reintento automático de plataforma.
```

### 2.3 Resumen de comportamiento

| Escenario | Resultado |
|---|---|
| Ambos envíos exitosos | Mensaje completado (removido de la cola) |
| CDP o AJO falla tras agotar reintentos (cualquiera de los dos, o ambos) | Mensaje se completa en `demolab-queue` (no se reintenta) y se escribe en `demolab-process-poison-queue` |
| Rate limit alcanzado en cualquier endpoint | La invocación espera (backpressure), no descarta ni falla |

---

## 3. Arquitectura y componentes

```
Azure Storage Queue (demolab-queue, stgdemolabv2)
      │  Queue Trigger (connection = QueueStorage, identity-based)
      ▼
┌───────────────────────────────┐
│ WhatsAppProcessFunction        │  ← Queue Trigger, Java 21
│  (Azure Function)              │
├───────────────────────────────┤
│ SecretCache                    │──► Azure Key Vault (kvdemolabv2, vía Managed Identity)
│ AdobeDispatchService            │
│ RateLimiter (x2, uno por endpoint)
│ HttpClient (java.net.http)      │
└──────────────┬─────────────────┘
               │ POST                          │ POST
               ▼                                ▼
   Adobe CDP Audience endpoint       Adobe AJO Webhook endpoint
```

### 3.1 Paquetes Java (misma separación por capas que Function 1)

```
com.example.function/    → Azure Function (entry point Queue Trigger)
com.example.service/     → lógica de negocio pura (AdobeDispatchService, rate limiting, mapeo de errores)
com.example.secrets/      → acceso a secretos con caché (SecretCache — mismo diseño que Function 1)
```

No se anticipa un paquete `security/` equivalente al de Function 1 (no hay verificación de firmas
en este proyecto) — si el mapeo de payload a los esquemas de CDP/AJO requiere lógica no trivial,
considerar un paquete `mapping/` adicional (ver decisión D1 en §9).

### 3.2 `WhatsAppProcessFunction` (entry point)

- `@FunctionName("WhatsAppProcess")`, `@QueueTrigger` (ver §2.1).
- Responsabilidades: leer las API keys vía `SecretCache`, delegar a `AdobeDispatchService` el envío
  a ambos endpoints, y capturar cualquier excepción para escribir el mensaje en la poison queue
  propia (`@QueueOutput` a `PROCESS_POISON_QUEUE_NAME`) en vez de dejarla propagar.
- No contiene lógica de rate limiting ni de construcción de requests — delega todo a
  `AdobeDispatchService`.

### 3.3 `AdobeDispatchService` (lógica pura, sin dependencias de Azure Functions)

```java
void dispatch(String rawEvent, String cdpApiKey, String ajoApiKey) throws Exception
    // aplica rate limiting + reintentos + llamada HTTP a ambos endpoints; lanza excepción
    // si alguno falla definitivamente tras agotar reintentos
```

Testeable con JUnit/Mockito inyectando un `HttpClient` (o wrapper) mockeado — sin necesidad de
levantar el runtime de Azure Functions ni pegarle a Adobe real.

### 3.4 `SecretCache` (mismo diseño que Function 1)

Mismo componente que en `whatsapp-webhook-function` (`azure-identity` + Managed Identity, cache TTL
10 min) — reusar tal cual, cambiando únicamente los nombres de secreto/env var. Orden de resolución
(alineado con Function 1 §5.4):

- Si `KEY_VAULT_URI` está seteada → se construye un `SecretClient` real y **Key Vault manda siempre**,
  incluso si la env var también está presente (evita que una API key real quede "pegada" en un App
  Setting sin que nadie lo note).
- Si `KEY_VAULT_URI` no está seteada → modo local: **no se construye ningún `SecretClient`** (no se
  intenta ninguna llamada de red) y se resuelve exclusivamente vía env var. Si la env var tampoco
  está presente, se lanza `IllegalStateException` con un mensaje explícito de configuración
  faltante, en vez de intentar conectar a un vault inexistente.

| Env var (local) | Secret name en Key Vault |
|---|---|
| `ADOBE_CDP_AUDIENCE_API_KEY` | `AdobeCdpAudienceApiKey` |
| `ADOBE_AJO_WEBHOOK_API_KEY` | `AdobeAjoWebhookApiKey` |

---

## 4. Rate limiting (30 TPS por endpoint, independientes)

- Un rate limiter tipo token-bucket **por endpoint** (`TokenBucketRateLimiter` propio, sin
  dependencias externas) — uno para CDP, uno para AJO, cada uno leyendo su propio
  `*_RATE_LIMIT_TPS` (default 30 si el App Setting no está presente).
- El limiter debe **esperar/bloquear** (backpressure) cuando se supera el TPS configurado, nunca
  descartar el evento.
- Desacoplado de la concurrencia del Queue Trigger (`host.json` → `extensions.queues.batchSize` /
  `newBatchThreshold`): el trigger puede procesar varios mensajes en paralelo, pero el rate
  limiter por endpoint es un singleton compartido entre esas invocaciones concurrentes — el límite
  aplica al agregado de llamadas salientes, no por invocación individual.
- Loguear (nivel INFO o WARNING) cuando se aplique backpressure significativo, para observabilidad
  en Application Insights.

---

## 5. Resiliencia

- **Reintentos**: backoff lineal ante fallas transitorias (timeout, `5xx`) de cada endpoint,
  independiente por endpoint — `ADOBE_INITIAL_BACKOFF_MILLIS * intento`. Default: 3 intentos
  (`ADOBE_MAX_ATTEMPTS`), backoff inicial 500ms (`ADOBE_INITIAL_BACKOFF_MILLIS`) →
  500ms/1000ms/1500ms.
- **Errores no reintentables** (`4xx` de Adobe, payload rechazado): no reintentar contra el mismo
  endpoint — loguear y tratar como fallo definitivo de ese envío.
- **Mensaje que falla definitivamente**: se completa manualmente (se remueve de `demolab-queue`) y
  se escribe en una poison queue propia (`demolab-process-poison-queue`) — no depende de
  `maxDequeueCount`/`demolab-queue-poison` nativas.
- **Idempotencia**: al no haber reintento de plataforma (§2.2 paso 4), un mensaje solo se procesa
  una vez; si falla, va directo a la poison queue en vez de volver a intentarse contra el endpoint
  que ya había tenido éxito.

---

## 6. Seguridad

- **API keys nunca en App Setting/env var plano en Azure**: viven en `kvdemolabv2`
  (`AdobeCdpAudienceApiKey`, `AdobeAjoWebhookApiKey`), resueltas vía `SecretCache` + Managed
  Identity — mismo patrón ya validado en Function 1, documentado en `KEYVAULT-SETUP.md` del
  proyecto hermano.
- **Endpoints URL sí como App Setting** (no son secretos).
- **Sin logging de contenido sensible**: no loguear el body completo del evento ni las API keys —
  solo metadatos (ej. `wamid`, resultado HTTP, latencia).
- **Function App sin acceso público** (`fnctdemolab02`, ya configurado) — solo se dispara por el
  trigger de cola, no expone ningún endpoint HTTP.
- **`ADOBE_DISABLE_SSL_VALIDATION`**: cuando es `true`, `AdobeDispatchService` instala un
  `X509TrustManager` permisivo que acepta cualquier certificado (necesario hoy porque los endpoints
  configurados son mocks de Pipedream, §9 D1). El default de `set-function-app-settings.ps1` cuando
  el setting no está presente en `local.settings.json` es `false`. **Debe quedar en `false`/ausente
  antes de apuntar a endpoints reales de Adobe.**

---

## 7. Infraestructura Azure requerida (ya provisionada — no recrear)

| Recurso | Estado |
|---|---|
| Function App `fnctdemolab02` (Linux, Java 21, plan `aspldemolabv2` P0v3) | ✅ existe |
| Managed Identity (system-assigned) en `fnctdemolab02` | ✅ habilitada |
| RBAC: `Storage Queue Data Contributor` + `Storage Blob Data Contributor` sobre `stgdemolabv2` | ✅ asignado |
| RBAC: `Key Vault Secrets User` sobre `kvdemolabv2` | ✅ asignado |
| VNet Integration a `subnet-function-outbound` (`vnedemolabv2`) | ✅ activa |
| `Enable public access: Off` (Disabled) | ✅ estado actual — se togglea a `On` temporalmente para desplegar, ver §11.1 |
| Cola `demolab-queue` en `stgdemolabv2` | ✅ existe |
| Secretos `AdobeCdpAudienceApiKey` / `AdobeAjoWebhookApiKey` en `kvdemolabv2` | ✅ creados (valor placeholder `test-key` hasta contar con las API keys reales de Adobe) |
| App Settings de este proyecto (§7.1) | ✅ configurados, incluye `KEY_VAULT_URI` |

### 7.1 App Settings requeridos en `fnctdemolab02`

| Variable | Valor / origen |
|---|---|
| `ADOBE_CDP_AUDIENCE_ENDPOINT` | URL del endpoint de ingestión CDP |
| `ADOBE_AJO_WEBHOOK_ENDPOINT` | URL del webhook AJO |
| `ADOBE_CDP_AUDIENCE_RATE_LIMIT_TPS` | `30` (opcional, default en código) |
| `ADOBE_AJO_WEBHOOK_RATE_LIMIT_TPS` | `30` (opcional, default en código) |
| `QUEUE_NAME` | `demolab-queue` |
| `QueueStorage__queueServiceUri` | `https://stgdemolabv2.queue.core.windows.net` |
| `QueueStorage__credential` | `managedidentity` |
| `KEY_VAULT_URI` | `https://kvdemolabv2.vault.azure.net/` |
| `FUNCTIONS_WORKER_RUNTIME` | `java` |

### 7.2 Local (`local.settings.json`, no versionado)

```json
{
  "IsEncrypted": false,
  "Values": {
    "AzureWebJobsStorage": "UseDevelopmentStorage=true",
    "QueueStorage": "UseDevelopmentStorage=true",
    "FUNCTIONS_WORKER_RUNTIME": "java",
    "QUEUE_NAME": "demolab-queue",
    "ADOBE_CDP_AUDIENCE_ENDPOINT": "https://<sandbox>.data.adobedc.net/ee/v2/...",
    "ADOBE_AJO_WEBHOOK_ENDPOINT": "https://<sandbox>.adobe.io/...",
    "ADOBE_CDP_AUDIENCE_API_KEY": "<api-key-de-prueba>",
    "ADOBE_AJO_WEBHOOK_API_KEY": "<api-key-de-prueba>",
    "ADOBE_CDP_AUDIENCE_RATE_LIMIT_TPS": "30",
    "ADOBE_AJO_WEBHOOK_RATE_LIMIT_TPS": "30"
  }
}
```

Requiere **Azurite** corriendo localmente para `AzureWebJobsStorage`/`QueueStorage`
(`UseDevelopmentStorage=true` no soporta managed identity, igual que en Function 1).

---

## 8. Stack técnico y dependencias (Maven)

- **Java 21**, empaquetado `jar`.
- `azure-functions-java-library` `3.3.0`
- `azure-identity` `1.13.3` + `azure-security-keyvault-secrets` `4.9.1` (para `SecretCache`)
- Rate limiting: `TokenBucketRateLimiter` propio (`AtomicLong` + `compareAndSet`, sin dependencias
  externas) — un limiter independiente por endpoint.
- Cliente HTTP: `java.net.http.HttpClient` (JDK 21, sin dependencia externa)
- Test: `junit-jupiter` `5.10.2`, `mockito-core` + `mockito-junit-jupiter` `5.11.0`
- Plugins de build (idénticos a Function 1):
  - `maven-compiler-plugin` (release 21)
  - `maven-surefire-plugin` — inyecta `ADOBE_CDP_AUDIENCE_API_KEY=test-key` /
    `ADOBE_AJO_WEBHOOK_API_KEY=test-key` como env vars de test; como `KEY_VAULT_URI` no está seteada
    durante los tests, `SecretCache` opera en modo local (sin `SecretClient`) y resuelve estas env
    vars directamente, sin depender de Key Vault
  - `jacoco-maven-plugin` `0.8.12`
  - `azure-functions-maven-plugin` `1.42.0` — `resourceGroup=RGDEMOLABV2`,
    `functionAppName=fnctdemolab02`, `location=eastus2` (properties del `pom.xml`, única fuente de
    verdad, igual que en Function 1)

---

## 9. Puntos abiertos

| # | Pregunta | Estado |
|---|---|---|
| D1 | ¿Cada endpoint Adobe espera el JSON crudo de WhatsApp tal cual, o requiere transformación a un esquema propio de CDP/AJO? | Pendiente — los endpoints configurados hoy son mocks de Pipedream (§7.1). Confirmar el formato de payload y el mecanismo de auth reales de Adobe antes de pasar a producción. Si requiere mapeo, agregar un paquete `mapping/` con lógica pura testeable y DTOs por endpoint. |

---

## 10. `host.json` (valores reales del proyecto desplegado)

```json
{
  "version": "2.0",
  "logging": {
    "applicationInsights": {
      "samplingExcludedTypes": "Request",
      "samplingSettings": { "isEnabled": true }
    }
  },
  "extensionBundle": {
    "id": "Microsoft.Azure.Functions.ExtensionBundle",
    "version": "[4.*, 5.0.0)"
  },
  "extensions": {
    "queues": {
      "maxDequeueCount": 5,
      "batchSize": 8,
      "newBatchThreshold": 4
    }
  }
}
```

`maxDequeueCount` queda como default del extension bundle pero es vestigial para el flujo de fallo
de negocio: el manejo de errores no depende de reintentos nativos de la cola ni de este valor (la
Function nunca deja que la excepción se propague — ver §2.1/§5). Solo aplicaría si el propio host
de Functions fallara antes de que el código de la Function alcance a correr.

---

## 11. Build y despliegue

Mismo patrón que Function 1 (ver `doc/guia-poc-subnet-nsg-webhook-whatsapp.md` del proyecto
hermano, secciones 5.1/5.5):

```powershell
mvn clean package --no-transfer-progress
func azure functionapp publish fnctdemolab02
```

`deploy.ps1` análogo al de `whatsapp-webhook-function`: lee `resourceGroup`/`functionAppName` desde
`pom.xml`, copia `.funcignore` al staging dir, publica desde ahí.

### 11.1 Nota: `publicNetworkAccess` bloquea `func azure functionapp publish`

`fnctdemolab02` solo es alcanzable por su private endpoint en `vnedemolabv2` cuando
`publicNetworkAccess: Disabled` (estado objetivo de seguridad, §6/§7). Esto rompe el flujo de
despliegue documentado arriba:

- **Síntoma**: `func azure functionapp publish` falla con `Timed out waiting for SCM to update the
  Environment Settings`. El mensaje es engañoso — no es un timeout de configuración, es que el
  sitio SCM/Kudu (`*.scm.<region>.azurewebsites.net`) devuelve `403 Forbidden` a nivel de
  plataforma porque el tráfico público nunca llega al private endpoint.
- **`az functionapp deploy --type zip` tampoco es un workaround**: aunque usa un token AAD (vía
  `az login`) en vez de basic auth, sigue siendo tráfico público — mismo `403` si
  `publicNetworkAccess` está en `Disabled`.
- **Workaround**: habilitar temporalmente `publicNetworkAccess: Enabled` en el Function App,
  desplegar (`az functionapp deploy --resource-group RGDEMOLABV2 --name fnctdemolab02 --src-path
  <zip-del-staging-dir> --type zip --async false`, empaquetando el contenido de
  `target\azure-functions\fnctdemolab02\` sin `local.settings.json`), aplicar
  `set-function-app-settings.ps1`, y volver a poner `publicNetworkAccess: Disabled` (no está
  automatizado — es un paso manual de cada despliegue).
- **Alternativa más robusta a futuro** (no implementada): desplegar desde un runner/jumpbox dentro
  de `vnedemolabv2` (o un self-hosted agent de CI/CD en la VNet), para no tener que exponer el SCM
  públicamente en cada despliegue.

---

## 12. Testing

Suite JUnit 5 + Mockito, sin llamadas reales a Adobe ni a Azure (HTTP client y `SecretClient`
mockeados). Estructura espejando `src/main/java`:

```
src/test/java/com/example/function/WhatsAppProcessFunctionTest.java
src/test/java/com/example/service/AdobeDispatchServiceTest.java
src/test/java/com/example/secrets/SecretCacheTest.java
```

Casos mínimos a cubrir en `AdobeDispatchServiceTest`:

| Test | Escenario | Resultado esperado |
|---|---|---|
| `dispatch_sendsToBothEndpoints_whenBothSucceed` | Ambas llamadas HTTP devuelven 2xx | No lanza excepción, se llamó una vez a cada endpoint |
| `dispatch_throws_whenCdpFailsAfterRetries` | CDP devuelve 5xx en todos los reintentos | Lanza excepción tras agotar reintentos |
| `dispatch_throws_whenAjoFailsAfterRetries` | AJO devuelve 5xx en todos los reintentos | Lanza excepción tras agotar reintentos |
| `dispatch_doesNotRetry_on4xxResponse` | Endpoint devuelve 4xx | No reintenta ese envío, falla directo |
| `dispatch_respectsRateLimit_perEndpoint` | Ráfaga de invocaciones concurrentes | El rate limiter aplica backpressure, no se exceden los TPS configurados |

`SecretCacheTest` reusa exactamente los casos de Function 1 (fallback env var, cache TTL, refresco,
degradación ante fallo de Key Vault) — mismo componente, distintos nombres de secreto.

---

## 13. Checklist para implementar desde cero

1. Crear los secretos `AdobeCdpAudienceApiKey` / `AdobeAjoWebhookApiKey` en `kvdemolabv2`.
2. Configurar los App Settings de §7.1 en `fnctdemolab02` (infraestructura de identidad/RBAC ya
   está lista, no recrear).
3. Confirmar el punto abierto D1 (§9) — formato de payload esperado por cada endpoint Adobe.
4. Crear proyecto Maven Java 21 con la estructura y dependencias de §8 (usar
   `whatsapp-webhook-function` como plantilla literal de `pom.xml`/`.funcignore`/`deploy.ps1`).
5. Implementar `WhatsAppProcessFunction` (Queue Trigger, capa fina) y `AdobeDispatchService`
   (lógica pura: rate limiting por endpoint, llamadas HTTP, reintentos) según §3.
6. Reusar `SecretCache` de Function 1 tal cual, cambiando solo los nombres de secreto/env var.
7. Escribir la suite de tests de §12 (sin dependencias reales de Azure ni Adobe).
8. Configurar `host.json` (§10), `local.settings.json` (§7.2, gitignored), `.funcignore`.
9. Escribir `deploy.ps1` (§11).
10. Probar en local contra Azurite + mocks/sandbox de Adobe antes de desplegar a `fnctdemolab02`.
11. Desplegar y validar end-to-end: encolar un mensaje de prueba en `demolab-queue` y confirmar que
    llega a ambos endpoints Adobe (o a sus sandbox/mocks), respetando el rate limit configurado.
