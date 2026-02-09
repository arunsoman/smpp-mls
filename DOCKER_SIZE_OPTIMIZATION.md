# Docker Image Size Optimization

## Current Situation

Your native image is **280MB**, which is larger than expected for a GraalVM native binary.

## Size Breakdown

Typical native image sizes:
- **Native binary alone**: 50-80MB
- **debian:bookworm-slim base**: ~80MB
- **System libraries (zlib, etc.)**: ~5-10MB
- **Total**: ~135-170MB expected

**Your 280MB suggests**:
- Possible debug symbols included
- Unoptimized native compilation
- Or base image bloat

## Solutions

### Option 1: Use Distroless (Recommended) ✅

**File**: `Dockerfile.distroless`

```dockerfile
FROM gcr.io/distroless/base-debian12
```

**Benefits**:
- Base image: ~11MB (vs 80MB debian-slim)
- More secure (minimal attack surface)
- Expected final size: **60-90MB**

**Build**:
```bash
docker build -f Dockerfile.distroless -t cascade/smpp-mls:distroless .
```

### Option 2: Use Alpine

**File**: `Dockerfile.alpine`

```dockerfile
FROM alpine:latest
```

**Benefits**:
- Base image: ~7MB
- Expected final size: **55-85MB**
- Requires `gcompat` for glibc compatibility

**Build**:
```bash
docker build -f Dockerfile.alpine -t cascade/smpp-mls:alpine .
```

### Option 3: Optimize Current Dockerfile

Add build optimizations to reduce native binary size:

```gradle
// In build.gradle
graalvmNative {
    binaries {
        main {
            buildArgs.add("--no-fallback")
            buildArgs.add("-O3")  // Optimize for size
            buildArgs.add("--gc=serial")  // Smaller GC
            buildArgs.add("-H:+RemoveUnusedSymbols")
            buildArgs.add("-H:-IncludeAllTimeZones")  // Remove unused timezones
        }
    }
}
```

## Quick Comparison

| Base Image | Base Size | Expected Total | Security | Compatibility |
|------------|-----------|----------------|----------|---------------|
| debian:bookworm-slim | 80MB | 135-170MB | Good | Excellent |
| distroless/base | 11MB | 60-90MB | Excellent | Excellent |
| alpine | 7MB | 55-85MB | Good | Good (needs gcompat) |

## Recommended Action

1. **Try distroless first** (best balance of size + security):
   ```bash
   docker build -f Dockerfile.distroless -t cascade/smpp-mls:latest .
   ```

2. **Check the actual binary size**:
   ```bash
   docker run --rm cascade/smpp-mls:latest ls -lh /app/smpp-mls
   ```

3. **If binary itself is huge (>100MB)**, add GraalVM optimization flags to `build.gradle`

## Why 280MB?

Possible causes:
1. **Debug symbols included** - add `-H:+StripDebugInfo` to build args
2. **All timezones included** - add `-H:-IncludeAllTimeZones`
3. **Reflection metadata** - large reflection config
4. **Base image bloat** - switch to distroless/alpine

## Next Steps

1. Build with distroless: `docker build -f Dockerfile.distroless -t cascade/smpp-mls:latest .`
2. Check new size: `docker images cascade/smpp-mls`
3. Expected result: **60-90MB** (3x smaller!)
