# TCP Forward

Forward TCP bidireccional en Java 8. Reenvía tráfico hacia un servidor remoto y registra todo el tráfico (hex + texto) para depuración. Migrado desde la versión original en Python 2.7.

## Requisitos

- Java 8+
- Maven 3.6+

## Build

```bash
mvn clean package
```

El resultado es `target/tcp-forward.jar` — un fat jar con todas las dependencias (SLF4J + Logback) incluidas vía `maven-shade-plugin`. Es auto-contenido y ejecutable.

## Uso

```bash
java -jar target/tcp-forward.jar \
  --local-port 9999 \
  --remote-host <REMOTE_HOST> \
  --remote-port <REMOTE_PORT>
```

Para escuchar en todas las interfaces:

```bash
java -jar target/tcp-forward.jar \
  --local-host 0.0.0.0 \
  --local-port 9999 \
  --remote-host <REMOTE_HOST> \
  --remote-port <REMOTE_PORT>
```

### Usando un archivo de configuración

Puedes evitar pasar todos los parámetros por línea de comandos usando un archivo de propiedades:

```bash
java -jar target/tcp-forward.jar --config config.properties
```

Se incluye un archivo de ejemplo en `src/main/resources/config.properties` que detalla todas las opciones, incluyendo la configuración para protocolos `length-prefixed`.

### Opciones

| Flag | Default | Descripción |
| --- | --- | --- |
| `--local-host HOST` | `127.0.0.1` | Host local donde escuchar |
| `--local-port PORT` | — **(requerido)** | Puerto local |
| `--remote-host HOST` | — **(requerido)** | Host remoto al que reenviar |
| `--remote-port PORT` | — **(requerido)** | Puerto remoto |
| `--timeout SECONDS` | `0` | Duración máxima de conexión (0 = persistente, sin timeout) |
| `--config FILE` | — | Ruta al archivo `.properties` con la configuración |
| `-h`, `--help` | — | Muestra ayuda |

## Logs

Se escriben simultáneamente a consola y a `logs/forward_YYYY-MM-DD.log` (relativo al directorio de trabajo). El formato es idéntico a la versión Python:

```
2026-04-01 13:49:34,249 - INFO  - Connection received from 192.168.1.100:59190
2026-04-01 13:49:34,388 - INFO  - Connected to remote server <REMOTE_HOST>:<REMOTE_PORT>
2026-04-01 13:49:34,249 - INFO  - [192.168.1.100:59190][client->remote] Received 322 bytes
2026-04-01 13:49:34,249 - DEBUG - [192.168.1.100:59190][client->remote] Hex: 0140f1f1...
2026-04-01 13:49:34,257 - DEBUG - [192.168.1.100:59190][client->remote] Forwarded 322 bytes
```

El nivel por defecto es `DEBUG` (incluye hex + texto de cada chunk). Para reducir el volumen editá `src/main/resources/logback.xml` y cambiá `<root level="DEBUG">` a `INFO`.

## Arquitectura

Tres clases principales:

- **`TCPForwarder`** — loop de `accept()` con `ServerSocket`, gestión del `ExecutorService` y shutdown.
- **`ClientHandler`** — por cada conexión entrante abre el socket remoto y lanza dos forwarders.
- **`DataForwarder`** — lee bytes de un socket y los escribe al otro, una instancia por dirección.

El modelo es thread-per-direction usando un `CachedThreadPool` con threads daemon. Cada conexión usa 3 threads del pool (1 handler + 2 forwarders).

### Diferencias con la versión Python

- En Python se usa `select()` porque `socket.settimeout()` no es thread-safe cuando dos threads comparten el mismo socket. En Java, `Socket.setSoTimeout()` es seguro de configurar una vez antes del read loop, por lo que un `read()` bloqueante con timeout corto es equivalente y más simple.
- El cierre de sockets en shutdown usa un `Set<Socket>` concurrente (`ConcurrentHashMap.newKeySet()`) en lugar de lista + lock.
- `shutdownOutput()` en Java es el equivalente a `shutdown(SHUT_WR)` en Python — propaga EOF al otro extremo sin matar el lado de lectura.

## Detener el forwarder

`Ctrl+C` (SIGINT) o `kill <pid>` (SIGTERM). El shutdown hook cierra el server socket, todos los sockets activos, y drena el ExecutorService con un periodo de gracia de 5 segundos.
