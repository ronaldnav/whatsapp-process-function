# whatsapp-process-function

Proyecto Maven de Azure Functions (Java 21) para despachar eventos de WhatsApp desde una Queue Trigger hacia Adobe CDP Audience y AJO Webhook con rate limiting y reintentos.

## Estructura

- `src/main/java/com/example/function/WhatsAppProcessFunction.java`: entry point de la Function (`@FunctionName("WhatsAppProcess")`).
- `src/main/java/com/example/service/AdobeDispatchService.java`: lógica de reintentos, rate limiting y HTTP.
- `src/main/java/com/example/secrets/SecretCache.java`: resolución de secretos con fallback local y caché TTL.

## Ejecución local

1. Instalar y ejecutar Azurite.
2. Crear un archivo `local.settings.json` con los valores del spec.
3. Ejecutar `mvn test`.
4. Ejecutar `mvn clean package` y luego `func start`.

## To-Do

- [ ] Centralizar `TokenBucketRateLimiter` (CDP y AJO) en Redis para que el rate limit sea global entre instancias de la Function, no por instancia.
- [ ] Crear function de envío de WhatsApp que almacene en Redis el `wamid` y el teléfono de cada mensaje enviado.
- [ ] `WhatsAppProcessFunction` debe consultar ese `wamid` en Redis para complementar su trama con el teléfono/contexto del envío original.
- [ ] Infraestructura: Azure Managed Redis, tier Balanced B1, Two-Node (High Availability), con Private Link en la VNet (sin acceso público, Private DNS Zone). Se descarta Azure Cache for Redis clásico por su retiro anunciado para el 30/04/2028.
