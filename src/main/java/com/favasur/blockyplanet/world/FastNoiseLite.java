package com.favasur.blockyplanet.world;

/**
 * FastNoiseLite — a self-contained 3D simplex/open simplex noise implementation.
 *
 * This is a minimal port of the public-domain FastNoiseLite library by Jordan Peck.
 * https://github.com/Auburn/FastNoiseLite
 *
 * Only includes the features we need: OpenSimplex2S with FBM fractal.
 */
public class FastNoiseLite {

    public enum NoiseType { OpenSimplex2S }
    public enum FractalType { None, FBM }

    private int seed = 1337;
    private double frequency = 0.01;
    private NoiseType noiseType = NoiseType.OpenSimplex2S;
    private FractalType fractalType = FractalType.None;
    private int fractalOctaves = 3;
    private double fractalLacunarity = 2.0;
    private double fractalGain = 0.5;
    private double fractalWeightedStrength = 0.0;

    private static final double SQRT3 = 1.7320508075688772935274463415059;
    private static final double F2 = 0.5 * (SQRT3 - 1.0);
    private static final double G2 = (3.0 - SQRT3) / 6.0;

    private static final int PRIME_X = 501125321;
    private static final int PRIME_Y = 1136930381;
    private static final int PRIME_Z = 1720413743;

    public void SetSeed(int seed) { this.seed = seed; }
    public void SetFrequency(double freq) { this.frequency = freq; }
    public void SetNoiseType(NoiseType type) { this.noiseType = type; }
    public void SetFractalType(FractalType type) { this.fractalType = type; }
    public void SetFractalOctaves(int octaves) { this.fractalOctaves = octaves; }
    public void SetFractalLacunarity(double lacunarity) { this.fractalLacunarity = lacunarity; }
    public void SetFractalGain(double gain) { this.fractalGain = gain; }

    public double GetNoise(double x, double y, double z) {
        x *= frequency;
        y *= frequency;
        z *= frequency;

        double noise = switch (noiseType) {
            case OpenSimplex2S -> OpenSimplex2SNoise(x, y, z);
        };

        if (fractalType == FractalType.FBM) {
            double amp = 1.0;
            double maxAmp = 0.0;
            double result = noise;

            for (int i = 1; i < fractalOctaves; i++) {
                x *= fractalLacunarity;
                y *= fractalLacunarity;
                z *= fractalLacunarity;
                amp *= fractalGain;
                result += switch (noiseType) {
                    case OpenSimplex2S -> OpenSimplex2SNoise(x, y, z) * amp;
                };
                maxAmp += amp;
            }

            noise = result / maxAmp;
        }

        return noise;
    }

    private static int Hash(int seed, int xPrimed, int yPrimed, int zPrimed) {
        int hash = seed ^ xPrimed ^ yPrimed ^ zPrimed;
        hash *= 0x27d4eb2d;
        return hash;
    }

    private static double GradCoord(int seed, int xPrimed, int yPrimed, int zPrimed, double xd, double yd, double zd) {
        int hash = Hash(seed, xPrimed, yPrimed, zPrimed);
        hash ^= hash >> 15;
        hash &= 63;

        double u, v, w;
        if (hash < 8) {
            u = xd; v = yd; w = zd;
        } else if (hash < 24) {
            u = yd; v = zd; w = xd;
        } else if (hash < 40) {
            u = zd; v = xd; w = yd;
        } else {
            u = xd; v = yd; w = zd;
        }

        int uh = ((hash >> 3) & 1) != 0 ? 1 : -1;
        int vh = ((hash >> 2) & 1) != 0 ? 1 : -1;
        int wh = ((hash >> 1) & 1) != 0 ? 1 : -1;

        u *= uh;
        v *= vh;
        w *= wh;

        return u + v + w;
    }

    private double OpenSimplex2SNoise(double x, double y, double z) {
        // Simple 3D OpenSimplex2S noise
        // Place a lattice of tetrahedra over 3D space and evaluate at the query point

        double s = (x + y + z) * F2;
        int i = FastFloor(x + s);
        int j = FastFloor(y + s);
        int k = FastFloor(z + s);

        double t = (i + j + k) * G2;
        double X0 = i - t;
        double Y0 = j - t;
        double Z0 = k - t;

        double x0 = x - X0;
        double y0 = y - Y0;
        double z0 = z - Z0;

        int i1, j1, k1;
        int i2, j2, k2;

        if (x0 >= y0) {
            if (y0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 1; k2 = 0;
            } else if (x0 >= z0) {
                i1 = 1; j1 = 0; k1 = 0;
                i2 = 1; j2 = 0; k2 = 1;
            } else {
                i1 = 0; j1 = 0; k1 = 1;
                i2 = 1; j2 = 0; k2 = 1;
            }
        } else {
            if (y0 < z0) {
                i1 = 0; j1 = 0; k1 = 1;
                i2 = 0; j2 = 1; k2 = 1;
            } else if (x0 < z0) {
                i1 = 0; j1 = 1; k1 = 0;
                i2 = 0; j2 = 1; k2 = 1;
            } else {
                i1 = 0; j1 = 1; k1 = 0;
                i2 = 1; j2 = 1; k2 = 0;
            }
        }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double z1 = z0 - k1 + G2;
        double x2 = x0 - i2 + 2.0 * G2;
        double y2 = y0 - j2 + 2.0 * G2;
        double z2 = z0 - k2 + 2.0 * G2;
        double x3 = x0 - 1.0 + 3.0 * G2;
        double y3 = y0 - 1.0 + 3.0 * G2;
        double z3 = z0 - 1.0 + 3.0 * G2;

        int seed2 = seed;
        int xPrimed = i * PRIME_X;
        int yPrimed = j * PRIME_Y;
        int zPrimed = k * PRIME_Z;

        double n0, n1, n2, n3;

        // Contribution 0
        double t0 = 0.6 - x0 * x0 - y0 * y0 - z0 * z0;
        if (t0 < 0) n0 = 0;
        else {
            t0 *= t0;
            n0 = t0 * t0 * GradCoord(seed2, xPrimed, yPrimed, zPrimed, x0, y0, z0);
        }

        // Contribution 1
        double t1 = 0.6 - x1 * x1 - y1 * y1 - z1 * z1;
        if (t1 < 0) n1 = 0;
        else {
            t1 *= t1;
            n1 = t1 * t1 * GradCoord(seed2, xPrimed + i1 * PRIME_X, yPrimed + j1 * PRIME_Y, zPrimed + k1 * PRIME_Z, x1, y1, z1);
        }

        // Contribution 2
        double t2 = 0.6 - x2 * x2 - y2 * y2 - z2 * z2;
        if (t2 < 0) n2 = 0;
        else {
            t2 *= t2;
            n2 = t2 * t2 * GradCoord(seed2, xPrimed + i2 * PRIME_X, yPrimed + j2 * PRIME_Y, zPrimed + k2 * PRIME_Z, x2, y2, z2);
        }

        // Contribution 3
        double t3 = 0.6 - x3 * x3 - y3 * y3 - z3 * z3;
        if (t3 < 0) n3 = 0;
        else {
            t3 *= t3;
            n3 = t3 * t3 * GradCoord(seed2, xPrimed + PRIME_X, yPrimed + PRIME_Y, zPrimed + PRIME_Z, x3, y3, z3);
        }

        return (n0 + n1 + n2 + n3) * 32.0;
    }

    private static int FastFloor(double f) {
        int fi = (int) f;
        return f < fi ? fi - 1 : fi;
    }
}
