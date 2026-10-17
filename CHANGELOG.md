# Registro de cambios | Changelog

## Alpha 0.1 — 17 de octubre de 2026

### Español — primero

- Se flexibiliza la confirmación de 1H/4H: 15m vuelve a ser el marco principal de entrada y los marcos superiores aportan contexto.
- El análisis multi-temporal descarta velas que todavía están abiertas para evitar tomar decisiones con datos incompletos.
- Se evita solicitar continuamente la misma vela cerrada; la caché se actualiza cuando corresponde.
- Las características del modelo estadístico usan spread, desequilibrio, microprecio y flujo reciente cuando existe un libro real y suficientemente reciente.
- El registro de decisión guarda timestamps, antigüedad de datos, origen/secuencia del libro y el precio de ejecución supuesto.
- El historial separa las comisiones de entrada y salida y conserva los supuestos de coste para facilitar auditorías.
- Se incorpora la importación de un respaldo JSON para recuperar historial y aprendizaje tras reinstalar la aplicación.

### English — secondary

- Higher-timeframe confirmation is less rigid: 15m is the primary entry timeframe, while 1H/4H provide context.
- Multi-timeframe analysis excludes candles that have not closed yet.
- The candle cache no longer repeatedly fetches the same closed candle.
- Statistical-model features use spread, imbalance, microprice, and recent trade flow when a real, sufficiently fresh order book is available.
- Decision audit records timestamps, data age, book source/sequence, and the assumed executable fill price.
- Trade history separates entry and exit fees and records cost assumptions for auditing.
- JSON backup import is available to restore trade history and learning after reinstalling the app.

## Pendiente de validar | Still to validate

### Español

- Comparar la estrategia base con MTF, Order Flow e IA activando y desactivando cada capa por separado.
- Contrastar una operación cerrada con las tarifas oficiales de la cuenta/exchange y verificar el cálculo de financiación.
- Medir la frecuencia de operaciones y el tiempo hasta la primera entrada con datos reales y una ventana de prueba reproducible.
- Confirmar la compilación de esta revisión y revisar los registros antes de publicar otro APK.

### English

- Compare the baseline strategy against MTF, Order Flow, and AI with each layer independently enabled and disabled.
- Reconcile a closed trade against the account's official exchange fees and verify funding calculations.
- Measure trade frequency and time to first entry using live data and a reproducible test window.
- Confirm this revision's build and inspect logs before publishing another APK.

> Los cambios mejoran la observabilidad y corrigen restricciones detectadas en el código; no demuestran rentabilidad. | These changes improve observability and address code-level restrictions; they do not demonstrate profitability.
