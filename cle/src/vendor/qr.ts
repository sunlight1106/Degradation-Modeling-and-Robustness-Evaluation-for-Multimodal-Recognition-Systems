// Minimal QR Model 2 encoder for local payment URLs.
// Fixed Version 4 / error correction L: 80 data codewords + 20 Reed-Solomon codewords.
const SIZE = 33
const DATA_CODEWORDS = 80
const ECC_CODEWORDS = 20

type Matrix = boolean[][]

function appendBits(bits: number[], value: number, length: number) {
  for (let i = length - 1; i >= 0; i--) bits.push(((value >>> i) & 1) !== 0 ? 1 : 0)
}

function multiply(x: number, y: number) {
  let z = 0
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d)
    z ^= ((y >>> i) & 1) * x
  }
  return z
}

function divisor(degree: number) {
  const result = new Uint8Array(degree)
  result[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = multiply(result[j], root)
      if (j + 1 < degree) result[j] ^= result[j + 1]
    }
    root = multiply(root, 2)
  }
  return result
}

function remainder(data: Uint8Array, degree: number) {
  const result = new Uint8Array(degree)
  const generator = divisor(degree)
  for (const value of data) {
    const factor = value ^ result[0]
    result.copyWithin(0, 1)
    result[degree - 1] = 0
    for (let i = 0; i < degree; i++) result[i] ^= multiply(generator[i], factor)
  }
  return result
}

function codewords(text: string) {
  const encoded = new TextEncoder().encode(text)
  if (encoded.length > 78) throw new Error('二维码链接过长，请缩短 PUBLIC_WEB_URL')
  const bits: number[] = []
  appendBits(bits, 0b0100, 4)
  appendBits(bits, encoded.length, 8)
  encoded.forEach(value => appendBits(bits, value, 8))
  const capacity = DATA_CODEWORDS * 8
  appendBits(bits, 0, Math.min(4, capacity - bits.length))
  while (bits.length % 8) bits.push(0)
  const data = Array.from({ length: bits.length / 8 }, (_, i) => {
    let value = 0
    for (let j = 0; j < 8; j++) value = (value << 1) | bits[i * 8 + j]
    return value
  })
  for (let pad = 0xec; data.length < DATA_CODEWORDS; pad ^= 0xec ^ 0x11) data.push(pad)
  const bytes = Uint8Array.from(data)
  return [...bytes, ...remainder(bytes, ECC_CODEWORDS)]
}

function baseMatrix() {
  const modules: Array<Array<boolean | null>> = Array.from({ length: SIZE }, () => Array(SIZE).fill(null))
  const reserved: boolean[][] = Array.from({ length: SIZE }, () => Array(SIZE).fill(false))
  const set = (x: number, y: number, value: boolean) => {
    if (x >= 0 && x < SIZE && y >= 0 && y < SIZE) { modules[y][x] = value; reserved[y][x] = true }
  }
  const finder = (cx: number, cy: number) => {
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const distance = Math.max(Math.abs(dx), Math.abs(dy))
      set(cx + dx, cy + dy, distance !== 2 && distance !== 4)
    }
  }
  finder(3, 3); finder(SIZE - 4, 3); finder(3, SIZE - 4)
  for (let i = 8; i < SIZE - 8; i++) {
    if (!reserved[6][i]) set(i, 6, i % 2 === 0)
    if (!reserved[i][6]) set(6, i, i % 2 === 0)
  }
  for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) set(26 + dx, 26 + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1)
  const formatCoordinates: Array<[number, number]> = []
  for (let i = 0; i <= 5; i++) formatCoordinates.push([8, i])
  formatCoordinates.push([8, 7], [8, 8], [7, 8])
  for (let i = 9; i < 15; i++) formatCoordinates.push([14 - i, 8])
  for (let i = 0; i < 8; i++) formatCoordinates.push([SIZE - 1 - i, 8])
  for (let i = 8; i < 15; i++) formatCoordinates.push([8, SIZE - 15 + i])
  formatCoordinates.forEach(([x, y]) => set(x, y, false))
  set(8, SIZE - 8, true)
  return { modules, reserved }
}

function maskBit(mask: number, x: number, y: number) {
  switch (mask) {
    case 0: return (x + y) % 2 === 0
    case 1: return y % 2 === 0
    case 2: return x % 3 === 0
    case 3: return (x + y) % 3 === 0
    case 4: return (Math.floor(y / 2) + Math.floor(x / 3)) % 2 === 0
    case 5: return (x * y) % 2 + (x * y) % 3 === 0
    case 6: return ((x * y) % 2 + (x * y) % 3) % 2 === 0
    default: return ((x + y) % 2 + (x * y) % 3) % 2 === 0
  }
}

function formatBits(mask: number) {
  const data = (1 << 3) | mask // Error correction L = 01
  let remainder = data
  for (let i = 0; i < 10; i++) remainder = (remainder << 1) ^ (((remainder >>> 9) & 1) * 0x537)
  return ((data << 10) | remainder) ^ 0x5412
}

function drawFormat(matrix: Matrix, mask: number) {
  const bits = formatBits(mask)
  const bit = (i: number) => ((bits >>> i) & 1) !== 0
  for (let i = 0; i <= 5; i++) matrix[i][8] = bit(i)
  matrix[7][8] = bit(6); matrix[8][8] = bit(7); matrix[8][7] = bit(8)
  for (let i = 9; i < 15; i++) matrix[8][14 - i] = bit(i)
  for (let i = 0; i < 8; i++) matrix[8][SIZE - 1 - i] = bit(i)
  for (let i = 8; i < 15; i++) matrix[SIZE - 15 + i][8] = bit(i)
  matrix[SIZE - 8][8] = true
}

function build(text: string, mask: number) {
  const { modules, reserved } = baseMatrix()
  const words = codewords(text)
  let bitIndex = 0
  let upward = true
  for (let right = SIZE - 1; right >= 1; right -= 2) {
    if (right === 6) right--
    for (let vertical = 0; vertical < SIZE; vertical++) {
      const y = upward ? SIZE - 1 - vertical : vertical
      for (let column = 0; column < 2; column++) {
        const x = right - column
        if (reserved[y][x]) continue
        let value = bitIndex < words.length * 8 && ((words[bitIndex >>> 3] >>> (7 - (bitIndex & 7))) & 1) !== 0
        bitIndex++
        if (maskBit(mask, x, y)) value = !value
        modules[y][x] = value
      }
    }
    upward = !upward
  }
  const result = modules.map(row => row.map(value => Boolean(value)))
  drawFormat(result, mask)
  return result
}

function penalty(matrix: Matrix) {
  let score = 0
  for (let axis = 0; axis < 2; axis++) for (let line = 0; line < SIZE; line++) {
    let runColor = false; let runLength = 0; let pattern = ''
    for (let index = 0; index < SIZE; index++) {
      const color = axis === 0 ? matrix[line][index] : matrix[index][line]
      if (index === 0 || color !== runColor) { if (runLength >= 5) score += runLength - 2; runColor = color; runLength = 1 } else runLength++
      pattern = (pattern + (color ? '1' : '0')).slice(-11)
      if (pattern === '00001011101' || pattern === '10111010000') score += 40
    }
    if (runLength >= 5) score += runLength - 2
  }
  for (let y = 0; y < SIZE - 1; y++) for (let x = 0; x < SIZE - 1; x++) {
    const value = matrix[y][x]
    if (value === matrix[y][x + 1] && value === matrix[y + 1][x] && value === matrix[y + 1][x + 1]) score += 3
  }
  const dark = matrix.flat().filter(Boolean).length
  score += Math.floor(Math.abs(dark * 20 - SIZE * SIZE * 10) / (SIZE * SIZE)) * 10
  return score
}

export function qrSvg(text: string, scale = 7, border = 4) {
  let best = build(text, 0); let bestPenalty = penalty(best)
  for (let mask = 1; mask < 8; mask++) { const candidate = build(text, mask); const value = penalty(candidate); if (value < bestPenalty) { best = candidate; bestPenalty = value } }
  const dimension = (SIZE + border * 2) * scale
  const paths: string[] = []
  for (let y = 0; y < SIZE; y++) for (let x = 0; x < SIZE; x++) if (best[y][x]) paths.push(`M${(x + border) * scale},${(y + border) * scale}h${scale}v${scale}h-${scale}z`)
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${dimension} ${dimension}" width="${dimension}" height="${dimension}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="${paths.join('')}" fill="#111"/></svg>`
}
