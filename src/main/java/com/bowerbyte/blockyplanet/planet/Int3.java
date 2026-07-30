package com.bowerbyte.blockyplanet.planet;

/**
 * Immutable 3D integer vector for block and chunk indices.
 */
public record Int3(int x, int y, int z) {

    public static final Int3 ZERO = new Int3(0, 0, 0);

    public Int3 add(Int3 other) {
        return new Int3(x + other.x, y + other.y, z + other.z);
    }

    public Int3 add(int dx, int dy, int dz) {
        return new Int3(x + dx, y + dy, z + dz);
    }

    public Int3 negate() {
        return new Int3(-x, -y, -z);
    }

    public Int3 mod(int modulus) {
        return new Int3(
            ((x % modulus) + modulus) % modulus,
            ((y % modulus) + modulus) % modulus,
            ((z % modulus) + modulus) % modulus
        );
    }

    public Int3 div(int divisor) {
        int floorDiv = Math.floorDiv(x, divisor);
        int floorDivY = Math.floorDiv(y, divisor);
        int floorDivZ = Math.floorDiv(z, divisor);
        return new Int3(floorDiv, floorDivY, floorDivZ);
    }
}
