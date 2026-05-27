# ZK-SNARK Gesture Proof Benchmark

Performance baseline and measurement template for the gnark Groth16
gesture cosine-similarity circuit (Tasks 89-93).

---

## Circuit Summary

| Parameter         | Value                                    |
|-------------------|------------------------------------------|
| Curve             | BN254 (128-bit security)                 |
| Proof system      | Groth16                                  |
| Private witness   | `EnrolledDescriptor` — 107 floats        |
| Public inputs     | `LiveDescriptor` — 107 floats, `IsMatch` |
| Constraint count  | ~2 400 R1CS constraints                  |
| Proof size        | ≤ 200 bytes (target: ~192 bytes)         |
| Source            | `zk/gesture_circuit.go`                  |

---

## Performance Contract

| Operation       | Target (p95)  | Hard limit    | Measurement device          |
|-----------------|---------------|---------------|-----------------------------|
| `generateProof` | < 3 000 ms    | < 5 000 ms    | Snapdragon 730G equivalent  |
| `verifyProof`   | < 60 ms       | < 100 ms      | Same                        |
| Key load (cold) | < 200 ms      | < 500 ms      | Asset extraction first call |

---

## Benchmark Results

> Fill in this table after running `GestureZkBenchmark` on a physical device.

### Reference device: _[fill in model, SoC, Android version]_

| Run | generateProof (ms) | verifyProof (ms) | Proof size (bytes) | Pass / Fail |
|-----|--------------------|------------------|--------------------|-------------|
| 1   |                    |                  |                    |             |
| 2   |                    |                  |                    |             |
| 3   |                    |                  |                    |             |
| 4   |                    |                  |                    |             |
| 5   |                    |                  |                    |             |
| p50 |                    |                  |                    |             |
| p95 |                    |                  |                    |             |

---

## How to Run

```bash
# 1. Build the gnark circuit and JNI library
cd zk/
go test ./...                            # validate circuit on host
gomobile bind -target=android/arm64 .   # produces libgesturezk.so + gesturezk.aar

# 2. Place outputs
cp gesturezk.aar ../app/libs/
cp android/arm64-v8a/libgesturezk.so ../app/src/main/jniLibs/arm64-v8a/

# 3. Generate proving/verifying keys (one-time per circuit change)
go run ./cmd/setup/main.go \
  --output ../app/src/main/assets/zk/

# 4. Run the Android benchmark on a connected device
./gradlew :app:connectedGmsDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.showerideas.aura.zk.GestureZkBenchmark
```

---

## Regression Threshold

A CI run fails the ZK benchmark gate if:

- `generateProof` p95 > 5 000 ms on the reference device
- `verifyProof` p95 > 100 ms on the reference device
- Proof size > 200 bytes

---

## Key Management

| Asset path                              | Contents                  | Regenerate when        |
|-----------------------------------------|---------------------------|------------------------|
| `assets/zk/gesture_proving_key.bin`     | Groth16 proving key       | Circuit changes (R1CS) |
| `assets/zk/gesture_verifying_key.bin`   | Groth16 verifying key     | Circuit changes (R1CS) |

Keys are bundled in the release APK/AAB. There is no remote key fetch; the
proving key is large (~10–50 MB depending on constraint count) and is loaded
from assets at first use, then cached to `filesDir`.

---

## Security Notes

- The enrolled gesture descriptor is the **private witness** — it never leaves
  the device and is never included in the proof bytes.
- The proof only attests that `cosine_sim(live, enrolled) ≥ 0.85`.
- Groth16 is non-interactive and simulation-extractable under the generic group
  model. Trusted setup was performed via `gomobile bind` with a deterministic
  entropy source seeded from the device identity key hash at enrollment time.
- See `zk/gesture_circuit.go` for the full constraint system.
