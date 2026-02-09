# Docker Deployment Guide

## Quick Start

### Production Deployment with ClickHouse

```bash
# Build and start services
docker-compose -f prod-docker-compose.yml up -d

# View logs
docker-compose -f prod-docker-compose.yml logs -f smpp-mls

# Stop services
docker-compose -f prod-docker-compose.yml down
```

## Architecture

The `prod-docker-compose.yml` sets up two services:

1. **shahy-clickhouse**: ClickHouse database for message archival
2. **smpp-mls**: The SMPP application

### Network Configuration

Both services run on the `smpp-network` bridge network, allowing them to communicate using service names as hostnames.

**ClickHouse hostname**: `shahy-clickhouse` (accessible within Docker network)

## Environment Variables

The application uses environment variables to configure ClickHouse connection:

| Variable | Default | Description |
|----------|---------|-------------|
| `CLICKHOUSE_URL` | `jdbc:clickhouse://localhost:8123/default` | JDBC URL for ClickHouse |
| `CLICKHOUSE_USERNAME` | `default` | ClickHouse username |
| `CLICKHOUSE_PASSWORD` | `` | ClickHouse password |
| `CLICKHOUSE_ARCHIVE_ENABLED` | `true` | Enable/disable archival |
| `CLICKHOUSE_ARCHIVE_AGE_MINUTES` | `5` | Archive messages older than X minutes |
| `CLICKHOUSE_ARCHIVE_SCHEDULE_CRON` | `0 */5 * * * ?` | Archive schedule (every 5 min) |
| `CLICKHOUSE_ARCHIVE_DATABASE` | `smpp_archive` | ClickHouse database name |

### Docker Environment

In `prod-docker-compose.yml`, the ClickHouse URL is set to:
```yaml
CLICKHOUSE_URL: jdbc:clickhouse://shahy-clickhouse:8123/default
```

This uses the Docker service name `shahy-clickhouse` as the hostname.

### Local Development

For local development (non-Docker), the default `localhost` is used:
```yaml
# application.yml default
clickhouse:
  url: ${CLICKHOUSE_URL:jdbc:clickhouse://localhost:8123/default}
```

## Customization

### Override Environment Variables

Create a `.env` file:

```bash
# .env
CLICKHOUSE_URL=jdbc:clickhouse://shahy-clickhouse:8123/default
CLICKHOUSE_USERNAME=admin
CLICKHOUSE_PASSWORD=secret123
CLICKHOUSE_ARCHIVE_AGE_MINUTES=10
```

Then use it:
```bash
docker-compose -f prod-docker-compose.yml --env-file .env up -d
```

### Custom Configuration

To use a different ClickHouse host:

```bash
docker-compose -f prod-docker-compose.yml up -d \
  -e CLICKHOUSE_URL=jdbc:clickhouse://my-custom-clickhouse:8123/default
```

## Ports

| Service | Port | Description |
|---------|------|-------------|
| smpp-mls | 2222 | Application HTTP API |
| shahy-clickhouse | 8123 | ClickHouse HTTP interface |
| shahy-clickhouse | 9000 | ClickHouse native protocol |

## Volumes

- `clickhouse-data`: Persistent storage for ClickHouse data

## Health Checks

ClickHouse has a health check that runs every 10 seconds:
```bash
clickhouse-client --query "SELECT 1"
```

The SMPP application waits for ClickHouse to be healthy before starting.

## Verification

### Check Services

```bash
# Check running containers
docker ps

# Check ClickHouse is accessible
docker exec shahy-clickhouse clickhouse-client --query "SELECT 1"

# Check application logs
docker logs smpp-mls
```

### Verify Archival

```bash
# Check archived messages
docker exec shahy-clickhouse clickhouse-client --query "
  SELECT COUNT(*) FROM smpp_archive.sms_outbound
"

# Check recent archives
docker exec shahy-clickhouse clickhouse-client --query "
  SELECT COUNT(*) FROM smpp_archive.sms_outbound 
  WHERE archived_at > now() - INTERVAL 10 MINUTE
"
```

## Troubleshooting

### ClickHouse Connection Failed

```bash
# Check ClickHouse is running
docker ps | grep clickhouse

# Check ClickHouse logs
docker logs shahy-clickhouse

# Test connection from smpp-mls container
docker exec smpp-mls ping shahy-clickhouse
```

### Application Won't Start

```bash
# Check application logs
docker logs smpp-mls

# Check environment variables
docker exec smpp-mls env | grep CLICKHOUSE
```

## Production Considerations

1. **Persistent Data**: ClickHouse data is stored in a Docker volume. Back it up regularly.
2. **Resource Limits**: Add resource limits in docker-compose.yml:
   ```yaml
   deploy:
     resources:
       limits:
         cpus: '2'
         memory: 2G
   ```
3. **Security**: Use strong passwords for ClickHouse in production
4. **Monitoring**: Expose Prometheus metrics on port 2222/actuator/prometheus
