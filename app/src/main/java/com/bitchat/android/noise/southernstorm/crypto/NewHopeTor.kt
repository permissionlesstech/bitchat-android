/*
 * Based on the public domain C reference code for New Hope.
 * This Java version is also placed into the public domain.
 * 
 * Original authors: Erdem Alkim, Léo Ducas, Thomas Pöppelmann, Peter Schwabe
 * Java port: Rhys Weatherley
 */
package com.bitchat.android.noise.southernstorm.crypto

/**
 * New Hope key exchange algorithm, "torref" variant.
 * 
 * This version of New Hope implements the alternative constant-time
 * method for generating the public "a" value for anonymity networks
 * like Tor.
 */
open class NewHopeTor : NewHope() {

    override fun uniform(coeffs: CharArray, seed: ByteArray) {
        val state = LongArray(25)
        val nblocks = 16
        val buf = ByteArray(SHAKE128_RATE * nblocks)
        val x = CharArray(buf.size / 2)

        try {
            shake128_absorb(state, seed, 0, SEEDBYTES)
            do {
                shake128_squeezeblocks(buf, 0, nblocks, state)
                for (i in 0 until buf.size step 2) {
                    x[i / 2] = ((buf[i].toInt() and 0xff) or ((buf[i + 1].toInt() and 0xff) shl 8)).toChar()
                }
            } while (discardtopoly(coeffs, x))
        } finally {
            state.fill(0L)
            buf.fill(0)
            x.fill(0.toChar())
        }
    }

    companion object {
        private fun discardtopoly(coeffs: CharArray, x: CharArray): Boolean {
            for (i in 0 until 16) {
                batcher84(x, i)
            }

            // Check whether we're safe
            var r = 0
            for (i in 1008 until 1024) {
                r = r or (61444 - x[i].code)
            }
            if ((r shr 31) != 0) return true

            // If we are, copy coefficients to polynomial
            x.copyInto(coeffs, destinationOffset = 0, startIndex = 0, endIndex = PARAM_N)
            return false
        }

        private fun batcher84(x: CharArray, offset: Int) {
            fun cmpSwap(idx1: Int, idx2: Int) {
                val off1 = offset + idx1
                val off2 = offset + idx2
                val c = 61444 - x[off1].code
                val t = (x[off1].code xor x[off2].code) and (c shr 31)
                x[off1] = (x[off1].code xor t).toChar()
                x[off2] = (x[off2].code xor t).toChar()
            }

            cmpSwap(0, 16); cmpSwap(32, 48); cmpSwap(0, 32); cmpSwap(16, 48); cmpSwap(16, 32)
            cmpSwap(64, 80); cmpSwap(96, 112); cmpSwap(64, 96); cmpSwap(80, 112); cmpSwap(80, 96)
            cmpSwap(0, 64); cmpSwap(32, 96); cmpSwap(32, 64); cmpSwap(16, 80); cmpSwap(48, 112); cmpSwap(48, 80); cmpSwap(16, 32); cmpSwap(48, 64); cmpSwap(80, 96)
            cmpSwap(128, 144); cmpSwap(160, 176); cmpSwap(128, 160); cmpSwap(144, 176); cmpSwap(144, 160)
            cmpSwap(192, 208); cmpSwap(224, 240); cmpSwap(192, 224); cmpSwap(208, 240); cmpSwap(208, 224)
            cmpSwap(128, 192); cmpSwap(160, 224); cmpSwap(160, 192); cmpSwap(144, 208); cmpSwap(176, 240); cmpSwap(176, 208); cmpSwap(144, 160); cmpSwap(176, 192); cmpSwap(208, 224)
            cmpSwap(0, 128); cmpSwap(64, 192); cmpSwap(64, 128); cmpSwap(32, 160); cmpSwap(96, 224); cmpSwap(96, 160); cmpSwap(32, 64); cmpSwap(96, 128); cmpSwap(160, 192)
            cmpSwap(16, 144); cmpSwap(80, 208); cmpSwap(80, 144); cmpSwap(48, 176); cmpSwap(112, 240); cmpSwap(112, 176); cmpSwap(48, 80); cmpSwap(112, 144); cmpSwap(176, 208)
            cmpSwap(16, 32); cmpSwap(48, 64); cmpSwap(80, 96); cmpSwap(112, 128); cmpSwap(144, 160); cmpSwap(176, 192); cmpSwap(208, 224)
            cmpSwap(256, 272); cmpSwap(288, 304); cmpSwap(256, 288); cmpSwap(272, 304); cmpSwap(272, 288)
            cmpSwap(320, 336); cmpSwap(352, 368); cmpSwap(320, 352); cmpSwap(336, 368); cmpSwap(336, 352)
            cmpSwap(256, 320); cmpSwap(288, 352); cmpSwap(288, 320); cmpSwap(272, 336); cmpSwap(304, 368); cmpSwap(304, 336); cmpSwap(272, 288); cmpSwap(304, 320); cmpSwap(336, 352)
            cmpSwap(384, 400); cmpSwap(416, 432); cmpSwap(384, 416); cmpSwap(400, 432); cmpSwap(400, 416)
            cmpSwap(448, 464); cmpSwap(480, 496); cmpSwap(448, 480); cmpSwap(464, 496); cmpSwap(464, 480)
            cmpSwap(384, 448); cmpSwap(416, 480); cmpSwap(416, 448); cmpSwap(400, 464); cmpSwap(432, 496); cmpSwap(432, 464); cmpSwap(400, 416); cmpSwap(432, 448); cmpSwap(464, 480)
            cmpSwap(256, 384); cmpSwap(320, 448); cmpSwap(320, 384); cmpSwap(288, 416); cmpSwap(352, 480); cmpSwap(352, 416); cmpSwap(288, 320); cmpSwap(352, 384); cmpSwap(416, 448)
            cmpSwap(272, 400); cmpSwap(336, 464); cmpSwap(336, 400); cmpSwap(304, 432); cmpSwap(368, 496); cmpSwap(368, 432); cmpSwap(304, 336); cmpSwap(368, 400); cmpSwap(432, 464)
            cmpSwap(272, 288); cmpSwap(304, 320); cmpSwap(336, 352); cmpSwap(368, 384); cmpSwap(400, 416); cmpSwap(432, 448); cmpSwap(464, 480)
            cmpSwap(0, 256); cmpSwap(128, 384); cmpSwap(128, 256); cmpSwap(64, 320); cmpSwap(192, 448); cmpSwap(192, 320); cmpSwap(64, 128); cmpSwap(192, 256); cmpSwap(320, 384)
            cmpSwap(32, 288); cmpSwap(160, 416); cmpSwap(160, 288); cmpSwap(96, 352); cmpSwap(224, 480); cmpSwap(224, 352); cmpSwap(96, 160); cmpSwap(224, 288); cmpSwap(352, 416)
            cmpSwap(32, 64); cmpSwap(96, 128); cmpSwap(160, 192); cmpSwap(224, 256); cmpSwap(288, 320); cmpSwap(352, 384); cmpSwap(416, 448)
            cmpSwap(16, 272); cmpSwap(144, 400); cmpSwap(144, 272); cmpSwap(80, 336); cmpSwap(208, 464); cmpSwap(208, 336); cmpSwap(80, 144); cmpSwap(208, 272); cmpSwap(336, 400)
            cmpSwap(48, 304); cmpSwap(176, 432); cmpSwap(176, 304); cmpSwap(112, 368); cmpSwap(240, 496); cmpSwap(240, 368); cmpSwap(112, 176); cmpSwap(240, 304); cmpSwap(368, 432)
            cmpSwap(48, 80); cmpSwap(112, 144); cmpSwap(176, 208); cmpSwap(240, 272); cmpSwap(304, 336); cmpSwap(368, 400); cmpSwap(432, 464)
            cmpSwap(16, 32); cmpSwap(48, 64); cmpSwap(80, 96); cmpSwap(112, 128); cmpSwap(144, 160); cmpSwap(176, 192); cmpSwap(208, 224); cmpSwap(240, 256); cmpSwap(272, 288); cmpSwap(304, 320); cmpSwap(336, 352); cmpSwap(368, 384); cmpSwap(400, 416); cmpSwap(432, 448); cmpSwap(464, 480)
            cmpSwap(512, 528); cmpSwap(544, 560); cmpSwap(512, 544); cmpSwap(528, 560); cmpSwap(528, 544)
            cmpSwap(576, 592); cmpSwap(608, 624); cmpSwap(576, 608); cmpSwap(592, 624); cmpSwap(592, 608)
            cmpSwap(512, 576); cmpSwap(544, 608); cmpSwap(544, 576); cmpSwap(528, 592); cmpSwap(560, 624); cmpSwap(560, 592); cmpSwap(528, 544); cmpSwap(560, 576); cmpSwap(592, 608)
            cmpSwap(640, 656); cmpSwap(672, 688); cmpSwap(640, 672); cmpSwap(656, 688); cmpSwap(656, 672)
            cmpSwap(704, 720); cmpSwap(736, 752); cmpSwap(704, 736); cmpSwap(720, 752); cmpSwap(720, 736)
            cmpSwap(640, 704); cmpSwap(672, 736); cmpSwap(672, 704); cmpSwap(656, 720); cmpSwap(688, 752); cmpSwap(688, 720); cmpSwap(656, 672); cmpSwap(688, 704); cmpSwap(720, 736)
            cmpSwap(512, 640); cmpSwap(576, 704); cmpSwap(576, 640); cmpSwap(544, 672); cmpSwap(608, 736); cmpSwap(608, 672); cmpSwap(544, 576); cmpSwap(608, 640); cmpSwap(672, 704)
            cmpSwap(528, 656); cmpSwap(592, 720); cmpSwap(592, 656); cmpSwap(560, 688); cmpSwap(624, 752); cmpSwap(624, 688); cmpSwap(560, 592); cmpSwap(624, 656); cmpSwap(688, 720)
            cmpSwap(528, 544); cmpSwap(560, 576); cmpSwap(592, 608); cmpSwap(624, 640); cmpSwap(656, 672); cmpSwap(688, 704); cmpSwap(720, 736)
            cmpSwap(768, 784); cmpSwap(800, 816); cmpSwap(768, 800); cmpSwap(784, 816); cmpSwap(784, 800)
            cmpSwap(832, 848); cmpSwap(864, 880); cmpSwap(832, 864); cmpSwap(848, 880); cmpSwap(848, 864)
            cmpSwap(768, 832); cmpSwap(800, 864); cmpSwap(800, 832); cmpSwap(784, 848); cmpSwap(816, 880); cmpSwap(816, 848); cmpSwap(784, 800); cmpSwap(816, 832); cmpSwap(848, 864)
            cmpSwap(896, 912); cmpSwap(928, 944); cmpSwap(896, 928); cmpSwap(912, 944); cmpSwap(912, 928)
            cmpSwap(960, 976); cmpSwap(992, 1008); cmpSwap(960, 992); cmpSwap(976, 1008); cmpSwap(976, 992)
            cmpSwap(896, 960); cmpSwap(928, 992); cmpSwap(928, 960); cmpSwap(912, 976); cmpSwap(944, 1008); cmpSwap(944, 976); cmpSwap(912, 928); cmpSwap(944, 960); cmpSwap(976, 992)
            cmpSwap(768, 896); cmpSwap(832, 960); cmpSwap(832, 896); cmpSwap(800, 928); cmpSwap(864, 992); cmpSwap(864, 928); cmpSwap(800, 832); cmpSwap(864, 896); cmpSwap(928, 960)
            cmpSwap(784, 912); cmpSwap(848, 976); cmpSwap(848, 912); cmpSwap(816, 944); cmpSwap(880, 1008); cmpSwap(880, 944); cmpSwap(816, 848); cmpSwap(880, 912); cmpSwap(944, 976)
            cmpSwap(784, 800); cmpSwap(816, 832); cmpSwap(848, 864); cmpSwap(880, 896); cmpSwap(912, 928); cmpSwap(944, 960); cmpSwap(976, 992)
            cmpSwap(512, 768); cmpSwap(640, 896); cmpSwap(640, 768); cmpSwap(576, 832); cmpSwap(704, 960); cmpSwap(704, 832); cmpSwap(576, 640); cmpSwap(704, 768); cmpSwap(832, 896)
            cmpSwap(544, 800); cmpSwap(672, 928); cmpSwap(672, 800); cmpSwap(608, 864); cmpSwap(736, 992); cmpSwap(736, 864); cmpSwap(608, 672); cmpSwap(736, 800); cmpSwap(864, 928)
            cmpSwap(544, 576); cmpSwap(608, 640); cmpSwap(672, 704); cmpSwap(736, 768); cmpSwap(800, 832); cmpSwap(864, 896); cmpSwap(928, 960)
            cmpSwap(528, 784); cmpSwap(656, 912); cmpSwap(656, 784); cmpSwap(592, 848); cmpSwap(720, 976); cmpSwap(720, 848); cmpSwap(592, 656); cmpSwap(720, 784); cmpSwap(848, 912)
            cmpSwap(560, 816); cmpSwap(688, 944); cmpSwap(688, 816); cmpSwap(624, 880); cmpSwap(752, 1008); cmpSwap(752, 880); cmpSwap(624, 688); cmpSwap(752, 816); cmpSwap(880, 944)
            cmpSwap(560, 592); cmpSwap(624, 656); cmpSwap(688, 720); cmpSwap(752, 784); cmpSwap(816, 848); cmpSwap(880, 912); cmpSwap(944, 976)
            cmpSwap(528, 544); cmpSwap(560, 576); cmpSwap(592, 608); cmpSwap(624, 640); cmpSwap(656, 672); cmpSwap(688, 704); cmpSwap(720, 736); cmpSwap(752, 768); cmpSwap(784, 800); cmpSwap(816, 832); cmpSwap(848, 864); cmpSwap(880, 896); cmpSwap(912, 928); cmpSwap(944, 960); cmpSwap(976, 992)
            cmpSwap(0, 512); cmpSwap(256, 768); cmpSwap(256, 512); cmpSwap(128, 640); cmpSwap(384, 896); cmpSwap(384, 640); cmpSwap(128, 256); cmpSwap(384, 512); cmpSwap(640, 768)
            cmpSwap(64, 576); cmpSwap(320, 832); cmpSwap(320, 576); cmpSwap(192, 704); cmpSwap(448, 960); cmpSwap(448, 704); cmpSwap(192, 320); cmpSwap(448, 576); cmpSwap(704, 832)
            cmpSwap(64, 128); cmpSwap(192, 256); cmpSwap(320, 384); cmpSwap(448, 512); cmpSwap(576, 640); cmpSwap(704, 768); cmpSwap(832, 896)
            cmpSwap(32, 544); cmpSwap(288, 800); cmpSwap(288, 544); cmpSwap(160, 672); cmpSwap(416, 928); cmpSwap(416, 672); cmpSwap(160, 288); cmpSwap(416, 544); cmpSwap(672, 800)
            cmpSwap(96, 608); cmpSwap(352, 864); cmpSwap(352, 608); cmpSwap(224, 736); cmpSwap(480, 992); cmpSwap(480, 736); cmpSwap(224, 352); cmpSwap(480, 608); cmpSwap(736, 864)
            cmpSwap(96, 160); cmpSwap(224, 288); cmpSwap(352, 416); cmpSwap(480, 544); cmpSwap(608, 672); cmpSwap(736, 800); cmpSwap(864, 928)
            cmpSwap(32, 64); cmpSwap(96, 128); cmpSwap(160, 192); cmpSwap(224, 256); cmpSwap(288, 320); cmpSwap(352, 384); cmpSwap(416, 448); cmpSwap(480, 512); cmpSwap(544, 576); cmpSwap(608, 640); cmpSwap(672, 704); cmpSwap(736, 768); cmpSwap(800, 832); cmpSwap(864, 896); cmpSwap(928, 960)
            cmpSwap(16, 528); cmpSwap(272, 784); cmpSwap(272, 528); cmpSwap(144, 656); cmpSwap(400, 912); cmpSwap(400, 656); cmpSwap(144, 272); cmpSwap(400, 528); cmpSwap(656, 784)
            cmpSwap(80, 592); cmpSwap(336, 848); cmpSwap(336, 592); cmpSwap(208, 720); cmpSwap(464, 976); cmpSwap(464, 720); cmpSwap(208, 336); cmpSwap(464, 592); cmpSwap(720, 848)
            cmpSwap(80, 144); cmpSwap(208, 272); cmpSwap(336, 400); cmpSwap(464, 528); cmpSwap(592, 656); cmpSwap(720, 784); cmpSwap(848, 912)
            cmpSwap(48, 560); cmpSwap(304, 816); cmpSwap(304, 560); cmpSwap(176, 688); cmpSwap(432, 944); cmpSwap(432, 688); cmpSwap(176, 304); cmpSwap(432, 560); cmpSwap(688, 816)
            cmpSwap(112, 624); cmpSwap(368, 880); cmpSwap(368, 624); cmpSwap(240, 752); cmpSwap(496, 1008); cmpSwap(496, 752); cmpSwap(240, 368); cmpSwap(496, 624); cmpSwap(752, 880)
            cmpSwap(112, 176); cmpSwap(240, 304); cmpSwap(368, 432); cmpSwap(496, 560); cmpSwap(624, 688); cmpSwap(752, 816); cmpSwap(880, 944)
            cmpSwap(48, 80); cmpSwap(112, 144); cmpSwap(176, 208); cmpSwap(240, 272); cmpSwap(304, 336); cmpSwap(368, 400); cmpSwap(432, 464); cmpSwap(496, 528); cmpSwap(560, 592); cmpSwap(624, 656); cmpSwap(688, 720); cmpSwap(752, 784); cmpSwap(816, 848); cmpSwap(880, 912); cmpSwap(944, 976)
            cmpSwap(16, 32); cmpSwap(48, 64); cmpSwap(80, 96); cmpSwap(112, 128); cmpSwap(144, 160); cmpSwap(176, 192); cmpSwap(208, 224); cmpSwap(240, 256); cmpSwap(272, 288); cmpSwap(304, 320); cmpSwap(336, 352); cmpSwap(368, 384); cmpSwap(400, 416); cmpSwap(432, 448); cmpSwap(464, 480); cmpSwap(496, 512); cmpSwap(528, 544); cmpSwap(560, 576); cmpSwap(592, 608); cmpSwap(624, 640); cmpSwap(656, 672); cmpSwap(688, 704); cmpSwap(720, 736); cmpSwap(752, 768); cmpSwap(784, 800); cmpSwap(816, 832); cmpSwap(848, 864); cmpSwap(880, 896); cmpSwap(912, 928); cmpSwap(944, 960); cmpSwap(976, 992)
            cmpSwap(0, 1024); cmpSwap(512, 1024); cmpSwap(256, 1280); cmpSwap(768, 1280); cmpSwap(256, 512); cmpSwap(768, 1024); cmpSwap(128, 1152); cmpSwap(640, 1152); cmpSwap(384, 640); cmpSwap(896, 1152); cmpSwap(128, 256); cmpSwap(384, 512); cmpSwap(640, 768); cmpSwap(896, 1024); cmpSwap(1152, 1280)
            cmpSwap(64, 1088); cmpSwap(576, 1088); cmpSwap(320, 576); cmpSwap(832, 1088); cmpSwap(192, 1216); cmpSwap(704, 1216); cmpSwap(448, 704); cmpSwap(960, 1216); cmpSwap(192, 320); cmpSwap(448, 576); cmpSwap(704, 832); cmpSwap(960, 1088); cmpSwap(64, 128); cmpSwap(192, 256); cmpSwap(320, 384); cmpSwap(448, 512); cmpSwap(576, 640); cmpSwap(704, 768); cmpSwap(832, 896); cmpSwap(960, 1024); cmpSwap(1088, 1152); cmpSwap(1216, 1280)
            cmpSwap(32, 1056); cmpSwap(544, 1056); cmpSwap(288, 1312); cmpSwap(800, 1312); cmpSwap(288, 544); cmpSwap(800, 1056); cmpSwap(160, 1184); cmpSwap(672, 1184); cmpSwap(416, 672); cmpSwap(928, 1184); cmpSwap(160, 288); cmpSwap(416, 544); cmpSwap(672, 800); cmpSwap(928, 1056); cmpSwap(1184, 1312)
            cmpSwap(96, 1120); cmpSwap(608, 1120); cmpSwap(352, 608); cmpSwap(864, 1120); cmpSwap(224, 1248); cmpSwap(736, 1248); cmpSwap(480, 736); cmpSwap(992, 1248); cmpSwap(224, 352); cmpSwap(480, 608); cmpSwap(736, 864); cmpSwap(992, 1120); cmpSwap(96, 160); cmpSwap(224, 288); cmpSwap(352, 416); cmpSwap(480, 544); cmpSwap(608, 672); cmpSwap(736, 800); cmpSwap(864, 928); cmpSwap(992, 1056); cmpSwap(1120, 1184); cmpSwap(1248, 1312)
            cmpSwap(32, 64); cmpSwap(96, 128); cmpSwap(160, 192); cmpSwap(224, 256); cmpSwap(288, 320); cmpSwap(352, 384); cmpSwap(416, 448); cmpSwap(480, 512); cmpSwap(544, 576); cmpSwap(608, 640); cmpSwap(672, 704); cmpSwap(736, 768); cmpSwap(800, 832); cmpSwap(864, 896); cmpSwap(928, 960); cmpSwap(992, 1024); cmpSwap(1056, 1088); cmpSwap(1120, 1152); cmpSwap(1184, 1216); cmpSwap(1248, 1280); cmpSwap(1312, 1344)
            cmpSwap(16, 1040); cmpSwap(528, 1040); cmpSwap(272, 1296); cmpSwap(784, 1296); cmpSwap(272, 528); cmpSwap(784, 1040); cmpSwap(144, 1168); cmpSwap(656, 1168); cmpSwap(400, 656); cmpSwap(912, 1168); cmpSwap(144, 272); cmpSwap(400, 528); cmpSwap(656, 784); cmpSwap(912, 1040); cmpSwap(1168, 1296)
            cmpSwap(80, 1104); cmpSwap(592, 1104); cmpSwap(336, 592); cmpSwap(848, 1104); cmpSwap(208, 1232); cmpSwap(720, 1232); cmpSwap(464, 720); cmpSwap(976, 1232); cmpSwap(208, 336); cmpSwap(464, 592); cmpSwap(720, 848); cmpSwap(976, 1104); cmpSwap(80, 144); cmpSwap(208, 272); cmpSwap(336, 400); cmpSwap(464, 528); cmpSwap(592, 656); cmpSwap(720, 784); cmpSwap(848, 912); cmpSwap(976, 1040); cmpSwap(1104, 1168); cmpSwap(1232, 1296)
            cmpSwap(48, 1072); cmpSwap(560, 1072); cmpSwap(304, 1328); cmpSwap(816, 1328); cmpSwap(304, 560); cmpSwap(816, 1072); cmpSwap(176, 1200); cmpSwap(688, 1200); cmpSwap(432, 688); cmpSwap(944, 1200); cmpSwap(176, 304); cmpSwap(432, 560); cmpSwap(688, 816); cmpSwap(944, 1072); cmpSwap(1200, 1328)
            cmpSwap(112, 1136); cmpSwap(624, 1136); cmpSwap(368, 624); cmpSwap(880, 1136); cmpSwap(240, 1264); cmpSwap(752, 1264); cmpSwap(496, 752); cmpSwap(1008, 1264); cmpSwap(240, 368); cmpSwap(496, 624); cmpSwap(752, 880); cmpSwap(1008, 1136); cmpSwap(112, 176); cmpSwap(240, 304); cmpSwap(368, 432); cmpSwap(496, 560); cmpSwap(624, 688); cmpSwap(752, 816); cmpSwap(880, 944); cmpSwap(1008, 1072); cmpSwap(1136, 1200); cmpSwap(1264, 1328)
            cmpSwap(48, 80); cmpSwap(112, 144); cmpSwap(176, 208); cmpSwap(240, 272); cmpSwap(304, 336); cmpSwap(368, 400); cmpSwap(432, 464); cmpSwap(496, 528); cmpSwap(560, 592); cmpSwap(624, 656); cmpSwap(688, 720); cmpSwap(752, 784); cmpSwap(816, 848); cmpSwap(880, 912); cmpSwap(944, 976); cmpSwap(1008, 1040); cmpSwap(1072, 1104); cmpSwap(1136, 1168); cmpSwap(1200, 1232); cmpSwap(1264, 1296); cmpSwap(1328, 1360)
            cmpSwap(16, 32); cmpSwap(48, 64); cmpSwap(80, 96); cmpSwap(112, 128); cmpSwap(144, 160); cmpSwap(176, 192); cmpSwap(208, 224); cmpSwap(240, 256); cmpSwap(272, 288); cmpSwap(304, 320); cmpSwap(336, 352); cmpSwap(368, 384); cmpSwap(400, 416); cmpSwap(432, 448); cmpSwap(464, 480); cmpSwap(496, 512); cmpSwap(528, 544); cmpSwap(560, 576); cmpSwap(592, 608); cmpSwap(624, 640); cmpSwap(656, 672); cmpSwap(688, 704); cmpSwap(720, 736); cmpSwap(752, 768); cmpSwap(784, 800); cmpSwap(816, 832); cmpSwap(848, 864); cmpSwap(880, 896); cmpSwap(912, 928); cmpSwap(944, 960); cmpSwap(976, 992); cmpSwap(1008, 1024); cmpSwap(1040, 1056); cmpSwap(1072, 1088); cmpSwap(1104, 1120); cmpSwap(1136, 1152); cmpSwap(1168, 1184); cmpSwap(1200, 1216); cmpSwap(1232, 1248); cmpSwap(1264, 1280); cmpSwap(1296, 1312); cmpSwap(1328, 1344); cmpSwap(1360, 1376)
        }
    }
}
