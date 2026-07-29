# whatsapp-process-function

Proyecto Maven de Azure Functions (Java 21) para despachar eventos de WhatsApp desde una Queue Trigger hacia Adobe CDP Audience y AJO Webhook con rate limiting y reintentos.

## Estructura

- `src/main/java/com/example/function/EventDispatcherFunction.java`: entry point de la Function.
- `src/main/java/com/example/service/AdobeDispatchService.java`: lógica de reintentos, rate limiting y HTTP.
- `src/main/java/com/example/secrets/SecretCache.java`: resolución de secretos con fallback local y caché TTL.

## Ejecución local

1. Instalar y ejecutar Azurite.
2. Crear un archivo `local.settings.json` con los valores del spec.
3. Ejecutar `mvn test`.
4. Ejecutar `mvn clean package` y luego `func start`.
