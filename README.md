# Bitcoin Futures Simulator 🤖📈
**Alpha 0.1 · Simulador experimental de futuros de BTC/USDT para Android · Kotlin · Paper trading**

[English version below](#bitcoin-futures-simulator-)

Este proyecto es un simulador open-source para experimentar con estrategias de trading de Bitcoin, datos de mercado, análisis multi-temporal y asistencia opcional de IA en el dispositivo. La estrategia principal actual es **Swing**: busca oportunidades de continuación de tendencia y utiliza reversión a la media únicamente en condiciones de rango.

> **Estado del proyecto:** experimental y en desarrollo. Las compilaciones correctas no demuestran rentabilidad. No se presenta como una estrategia validada ni como un sistema listo para operar con dinero real.

## 🚀 Qué incluye actualmente

- **Estrategia Bitcoin Swing:** analiza velas de 15 minutos y confirma el contexto con marcos de 1H y 4H. Usa EMA, RSI, ATR y bandas basadas en desviación estándar para identificar posibles entradas de tendencia o de reversión a la media en rango.
- **Análisis multi-temporal:** la aplicación calcula contexto de mercado para 1m, 5m, 15m, 1H y 4H.
- **Order Flow y libro de órdenes:** integra datos de operaciones y libro de órdenes de Pionex Futures, con métricas como desequilibrio de profundidad, spread y microprecio. También mantiene conexiones de cotización alternativas y fuentes REST de respaldo.
- **Gemini Nano opcional:** mediante Android AICore y ML Kit GenAI, cuando el dispositivo lo admite y el modelo está disponible. Se usa para evaluar señales candidatas, analizar titulares y generar observaciones sobre operaciones cerradas. La estrategia base sigue siendo código Kotlin; la IA puede no estar disponible en todos los dispositivos.
- **Aprendizaje estadístico:** conserva datos del motor de aprendizaje y el historial de operaciones simuladas para apoyar el análisis posterior.
- **Gestión de riesgo simulada:** calcula tamaño de posición, stop-loss, objetivo y costes estimados de operación.
- **Paper trading:** las operaciones del motor son simuladas. No se conectan wallets ni se envían órdenes de trading reales.

## 🧭 Estrategia actual

El motor principal ya no debe describirse como un bot de scalping puro. La lógica actual es **Swing**:

- En condiciones de tendencia, busca alineación de medias y confirmación en marcos temporales superiores.
- En condiciones de rango, permite señales de reversión a la media bajo filtros de volatilidad y RSI.
- Los stops y objetivos se calculan a partir del ATR y de límites mínimos relativos al precio.
- La selección de entradas es deliberadamente selectiva; puede pasar largos periodos sin abrir operaciones si no se cumplen los filtros.

Los indicadores y filtros son heurísticas experimentales, no una garantía de que el mercado esté correctamente clasificado. El rendimiento debe evaluarse con pruebas reproducibles y costes realistas.

## 🛠️ Tecnologías

- **Lenguaje:** Kotlin
- **Android:** compile/target SDK 35; mínimo SDK 26
- **IA en dispositivo:** `com.google.mlkit:genai-prompt:1.0.0-beta2`
- **Red y tiempo real:** OkHttp 4.12.0, WebSockets y solicitudes HTTP
- **Asincronía:** Kotlin Coroutines

## 📥 Ejecutar el proyecto

1. Clona o descarga el repositorio.
2. Abre el proyecto en **Android Studio**.
3. Sincroniza Gradle y compila la aplicación.
4. Instala el APK en un dispositivo Android compatible.

La aplicación necesita acceso a Internet para obtener datos de mercado y titulares. Las funciones de Gemini Nano requieren un dispositivo compatible con Android AICore, el componente disponible y el modelo preparado; si no están disponibles, las funciones de IA pueden quedar inactivas. No se garantiza compatibilidad con un modelo concreto de teléfono.

## 🤝 Contribuir

¡Se agradecen las revisiones, los informes de errores y las mejoras! Algunas áreas útiles:

- Pruebas unitarias de indicadores, señales, tamaño de posición y cálculo de PnL.
- Comparación reproducible entre la estrategia base y las capas MTF, Order Flow e IA, activándolas por separado.
- Verificación de comisiones, deslizamiento y financiación con operaciones cerradas y tarifas oficiales del exchange.
- Validación de timestamps de velas, libro, decisión y precio ejecutable.
- Robustez ante desconexiones y datos antiguos.
- Evaluación histórica sin *look-ahead bias*, con velas cerradas y una referencia sencilla.
- Explicabilidad de las decisiones, métricas y mejoras de la interfaz.
- Documentación, accesibilidad y compatibilidad entre dispositivos.

Antes de enviar un cambio, describe el problema, explica la solución y adjunta resultados reproducibles cuando sea posible. Abre un **Issue** para debatir una propuesta o envía un **Pull Request**.

## 📄 Licencia

El código se distribuye bajo la licencia MIT. Consulta el archivo [LICENSE](LICENSE).

## ⚠️ Limitaciones y aviso

Este software se ofrece con fines educativos y de investigación; **no es asesoramiento financiero**. Es un experimento en evolución, no una promesa de rentabilidad. El *paper trading* no reproduce perfectamente latencia, ejecución, liquidez, deslizamiento, comisiones, financiación ni liquidaciones de un exchange. Los costes son estimaciones y pueden no coincidir con las condiciones de una cuenta real.

No uses resultados simulados como prueba suficiente para arriesgar dinero. Revisa el código, valida los datos y realiza pruebas independientes antes de sacar conclusiones.

---

# Bitcoin Futures Simulator 🤖📈

**Alpha 0.1 · Experimental BTC/USDT futures simulator for Android · Kotlin · Paper trading**

This open-source project is a sandbox for experimenting with Bitcoin trading strategies, market data, multi-timeframe analysis, and optional on-device AI assistance. Its current primary strategy is **Swing trading**: it looks for trend-continuation opportunities and only enables mean-reversion signals in range-bound conditions.

> **Project status:** experimental and under active development. A successful build does not demonstrate profitability. This is not a validated strategy or a system ready for real-money trading.

## 🚀 Current features

- **Bitcoin Swing strategy:** analyzes 15-minute candles and confirms market context using 1H and 4H timeframes. It uses EMAs, RSI, ATR, and standard-deviation bands to identify possible trend or range mean-reversion entries.
- **Multi-timeframe analysis:** calculates market context for 1m, 5m, 15m, 1H, and 4H.
- **Order Flow and order book:** consumes Pionex Futures trade and order-book data, with metrics including depth imbalance, spread, and microprice. It also maintains alternative quote connections and REST data fallbacks.
- **Optional Gemini Nano:** through Android AICore and ML Kit GenAI, when supported and available on the device. It can evaluate candidate signals, analyze headlines, and produce notes on closed trades. The base strategy is implemented in Kotlin; AI features may be unavailable on some devices.
- **Statistical learning:** retains learning-engine data and simulated trade history to support further analysis.
- **Simulated risk management:** calculates position size, stop-loss, target, and estimated trading costs.
- **Paper trading:** trades generated by the engine are simulated. It does not connect wallets or submit real trading orders.

## 🧭 Current strategy

The main engine should no longer be described as a pure scalping bot. Its current approach is **Swing trading**:

- In trending conditions, it looks for moving-average alignment and confirmation from higher timeframes.
- In range-bound conditions, it allows mean-reversion signals subject to volatility and RSI filters.
- Stops and targets are derived from ATR and minimum price-relative thresholds.
- Entry filters are selective, so the system may go for extended periods without opening a trade when no setup qualifies.

These indicators and filters are experimental heuristics, not a guarantee that market regimes are classified correctly. Performance should be assessed with reproducible tests and realistic costs.

## 🛠️ Tech stack

- **Language:** Kotlin
- **Android:** compile/target SDK 35; minimum SDK 26
- **On-device AI:** `com.google.mlkit:genai-prompt:1.0.0-beta2`
- **Networking and real-time data:** OkHttp 4.12.0, WebSockets, and HTTP requests
- **Asynchronous tasks:** Kotlin Coroutines

## 📥 Getting started

1. Clone or download the repository.
2. Open the project in **Android Studio**.
3. Sync Gradle and build the app.
4. Install the APK on a compatible Android device.

Internet access is required to retrieve market data and headlines. Gemini Nano features require a compatible device, Android AICore availability, and a prepared model; AI features may remain inactive otherwise. Compatibility with any specific phone model is not guaranteed.

## 🤝 Contributing

Code reviews, bug reports, and improvements are welcome. Useful areas include:

- Unit tests for indicators, signals, position sizing, and PnL accounting.
- Reproducible comparison between the baseline and MTF, Order Flow, and AI layers, toggled independently.
- Verification of fees, slippage, and funding using closed trades and official exchange rates.
- Timestamp validation for candles, order book, decisions, and executable prices.
- Resilience to disconnections and stale data.
- Historical evaluation without look-ahead bias, using closed candles and a simple baseline.
- Decision explainability, metrics, and UI improvements.
- Documentation, accessibility, and device compatibility.

When submitting a change, describe the problem and solution, and include reproducible results where possible. Open an **Issue** to discuss an idea or submit a **Pull Request**.

## 📄 License

The code is distributed under the MIT License. See [LICENSE](LICENSE).

## ⚠️ Limitations and disclaimer

This software is for educational and research purposes only and is **not financial advice**. It is an evolving experiment, not a promise of profitability. Paper trading cannot perfectly reproduce exchange latency, execution, liquidity, slippage, fees, funding, or liquidation. Costs are estimates and may not match the terms of a real account.

Do not treat simulated results as sufficient evidence to risk money. Review the code, validate the data, and run independent tests before drawing conclusions.
