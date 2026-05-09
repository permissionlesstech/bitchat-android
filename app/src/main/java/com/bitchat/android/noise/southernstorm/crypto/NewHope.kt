/*
 * Based on the public domain C reference code for New Hope.
 * This Java version is also placed into the public domain.
 * 
 * Original authors: Erdem Alkim, Léo Ducas, Thomas Pöppelmann, Peter Schwabe
 * Java port: Rhys Weatherley
 */

package com.bitchat.android.noise.southernstorm.crypto;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * NewHope key exchange algorithm.
 * 
 * This class implements the standard "ref" version of the New Hope
 * algorithm.
 * 
 * @see NewHopeTor
 */
public class NewHope {

	// -------------- params.h --------------
	
	static final int PARAM_N = 1024;
	static final int PARAM_Q = 12289;
	static final int POLY_BYTES = 1792;
	static final int SEEDBYTES = 32;
	static final int RECBYTES = 256;

	/**
	 * Number of bytes in the public key value sent by Alice.
	 */
	public static final int SENDABYTES = POLY_BYTES + SEEDBYTES;
	
	/**
	 * Number of bytes in the public key value sent by Bob.
	 */
	public static final int SENDBBYTES = POLY_BYTES + RECBYTES;
	
	/**
	 * Number of bytes in shared secret values computed by shareda() and sharedb().
	 */
	public static final int SHAREDBYTES = 32;

	// -------------- newhope.c --------------

	private Poly sk;

	/**
	 * Constructs a NewHope object.
	 */
	public NewHope()
	{
		sk = null;
	}

	@Override
	protected void finalize()
	{
		destroy();
	}

	/**
	 * Destroys sensitive material in this object.
	 * 
	 * This function should be called once the application has finished
	 * with the private key contained in this object.  This function
	 * will also be called when the object is finalized, but the point
	 * of finalization is unpredictable.  This function provides a more
	 * predictable place where the sensitive data is destroyed.
	 */
	public void destroy()
	{
		if (sk != null) {
			sk.destroy();
			sk = null;
		}
	}

	/**
	 * Generates the keypair for Alice.
	 * 
	 * @param send Buffer to place the public key for Alice in, to be sent to Bob.
	 * @param sendOffset Offset of the first byte in the send buffer to populate.
	 * 
	 * The send buffer must have space for at least NewHope.SENDABYTES bytes
	 * starting at sendOffset.
	 * 
	 * @see #sharedb(byte[], int, byte[], int, byte[], int)
   * @see #shareda(byte[], int, byte[], int)
	 */
	public void keygen(byte[] send, int sendOffset)
	{
	  Poly a = new Poly();
	  Poly e = new Poly();
	  Poly r = new Poly();
	  Poly pk = new Poly();
	  byte[] seed = new byte [SEEDBYTES + 32];
	  byte[] noiseseed = new byte [32];

	  try {
		  randombytes(seed);
		  sha3256(seed, 0, seed, 0, SEEDBYTES); /* Don't send output of system RNG */
		  System.arraycopy(seed, SEEDBYTES, noiseseed, 0, 32);
	
		  uniform(a.coeffs, seed);
	
		  if (sk == null)
			  sk = new Poly();
		  sk.getnoise(noiseseed,(byte)0);
		  sk.ntt();
	
		  e.getnoise(noiseseed,(byte)1);
		  e.ntt();
	
		  r.pointwise(sk,a);
		  pk.add(e,r);
	
		  encode_a(send, sendOffset, pk, seed);
	  } finally {
		  a.destroy();
		  e.destroy();
		  r.destroy();
		  pk.destroy();
		  Arrays.fill(seed, (byte)0);
		  Arrays.fill(noiseseed, (byte)0);
	  }
	}

	/**
	 * Generates the public key and shared secret for Bob.
	 * 
	 * @param sharedkey Buffer to place the shared secret for Bob in.
	 * @param sharedkeyOffset Offset of the first byte in the sharedkey buffer to populate.
	 * @param send Buffer to place the public key for Bob in to be sent to Alice.
	 * @param sendOffset Offset of the first byte in the send buffer to populate.
	 * @param received Buffer containing the public key value received from Alice.
	 * @param receivedOffset Offset of the first byte of the value received from Alice.
	 * 
	 * The sharedkey buffer must have space for at least NewHope.SHAREDBYTES
	 * bytes starting at sharedkeyOffset.
	 * 
	 * The send buffer must have space for at least NewHope.SENDBBYTES bytes
	 * starting at sendOffset.
	 * 
	 * The received buffer must have space for at least NewHope.SENDABYTES
	 * bytes starting at receivedOffset.
	 * 
	 * @see #shareda(byte[], int, byte[], int)
   * @see #keygen(byte[], int)
	 */
	public void sharedb(byte[] sharedkey, int sharedkeyOffset,
						byte[] send, int sendOffset,
						byte[] received, int receivedOffset)
	{
	  Poly sp = new Poly();
	  Poly ep = new Poly();
	  Poly v = new Poly();
	  Poly a = new Poly();
	  Poly pka = new Poly();
	  Poly c = new Poly();
	  Poly epp = new Poly();
	  Poly bp = new Poly();
	  byte[] seed = new byte [SEEDBYTES];
	  byte[] noiseseed = new byte [32];
	  byte[] skey = new byte [32];

	  try {
		  randombytes(noiseseed);
	
		  decode_a(pka, seed, received, receivedOffset);
		  uniform(a.coeffs, seed);
	
		  sp.getnoise(noiseseed,(byte)0);
		  sp.ntt();
		  ep.getnoise(noiseseed,(byte)1);
		  ep.ntt();
	
		  bp.pointwise(a, sp);
		  bp.add(bp, ep);
	
		  v.pointwise(pka, sp);
		  v.invntt();
	
		  epp.getnoise(noiseseed,(byte)2);
		  v.add(v, epp);
	
		  helprec(c, v, noiseseed, (byte)3);
	
		  encode_b(send, sendOffset, bp, c);
	
		  rec(skey, v, c);
	
		  sha3256(sharedkey, sharedkeyOffset, skey, 0, 32);
	  } finally {
		  sp.destroy();
		  ep.destroy();
		  v.destroy();
		  a.destroy();
		  pka.destroy();
		  c.destroy();
		  epp.destroy();
		  bp.destroy();
		  Arrays.fill(seed, (byte)0);
		  Arrays.fill(noiseseed, (byte)0);
		  Arrays.fill(skey, (byte)0);
	  }
	}

	/**
	 * Generates the shared secret for Alice.
	 * 
	 * @param sharedkey Buffer to place the shared secret for Alice in.
	 * @param sharedkeyOffset Offset of the first byte in the sharedkey buffer to populate.
	 * @param received Buffer containing the public key value received from Bob.
	 * @param receivedOffset Offset of the first byte of the value received from Bob.
	 * 
	 * The sharedkey buffer must have space for at least NewHope.SHAREDBYTES
	 * bytes starting at sharedkeyOffset.
	 * 
	 * The received buffer must have space for at least NewHope.SENDBBYTES bytes
	 * starting at receivedOffset.
	 * 
	 * @see #shareda(byte[], int, byte[], int)
   * @see #keygen(byte[], int)
	 */
	public void shareda(byte[] sharedkey, int sharedkeyOffset,
						byte[] received, int receivedOffset)
	{
	  Poly v = new Poly();
	  Poly bp = new Poly();
	  Poly c = new Poly();
	  byte[] skey = new byte [32];

	  try {
		  decode_b(bp, c, received, receivedOffset);
	
		  v.pointwise(sk,bp);
		  v.invntt();
	
		  rec(skey, v, c);
		  
		  sha3256(sharedkey, sharedkeyOffset, skey, 0, 32);
	  } finally {
		  v.destroy();
		  bp.destroy();
		  c.destroy();
		  Arrays.fill(skey, (byte)0);
	  }
	}

	/**
	 * Generates random bytes for use in the NewHope implementation.
	 * 
	 * @param buffer The buffer to fill with random bytes.
	 * 
	 * This function may be overridden in subclasses to provide a better
	 * random number generator or to provide static data for test vectors.
	 */
	protected void randombytes(byte[] buffer)
	{
		SecureRandom random = new SecureRandom();
		random.nextBytes(buffer);
	}

	private static void encode_a(byte[] r, int roffset, Poly pk, byte[] seed)
	{
	  int i;
	  pk.tobytes(r, roffset);
	  for(i=0;i<SEEDBYTES;i++)
	    r[POLY_BYTES+roffset+i] = seed[i];
	}

	private static void decode_a(Poly pk, byte[] seed, byte[] r, int roffset)
	{
	  int i;
	  pk.frombytes(r, roffset);
	  for(i=0;i<SEEDBYTES;i++)
	    seed[i] = r[POLY_BYTES+roffset+i];
	}

	private static void encode_b(byte[] r, int roffset, Poly b, Poly c)
	{
	  int i;
	  b.tobytes(r,roffset);
	  for(i=0;i<PARAM_N/4;i++)
	    r[POLY_BYTES+roffset+i] = (byte)(c.coeffs[4*i] | (c.coeffs[4*i+1] << 2) | (c.coeffs[4*i+2] << 4) | (c.coeffs[4*i+3] << 6));
	}

	private static void decode_b(Poly b, Poly c, byte[] r, int roffset)
	{
	  int i;
	  b.frombytes(r, roffset);
	  for(i=0;i<PARAM_N/4;i++)
	  {
	    c.coeffs[4*i+0] = (char)( r[POLY_BYTES+roffset+i]       & 0x03);
	    c.coeffs[4*i+1] = (char)((r[POLY_BYTES+roffset+i] >> 2) & 0x03);
	    c.coeffs[4*i+2] = (char)((r[POLY_BYTES+roffset+i] >> 4) & 0x03);
	    c.coeffs[4*i+3] = (char)(((r[POLY_BYTES+roffset+i] & 0xff) >> 6));
	  }
	}

	// -------------- poly.c --------------
	
	private class Poly
	{
		public char[] coeffs;

		public Poly()
		{
			coeffs = new char [PARAM_N];
		}
		
		protected void finalize()
		{
			destroy();
		}
		
		public void destroy()
		{
			Arrays.fill(coeffs, (char)0);
		}
		
		public void frombytes(byte[] a, int offset)
		{
			int i;
			for (i = 0; i < PARAM_N/4; i++)
			{
			    coeffs[4*i+0] = (char)(                                   (a[offset+7*i+0] & 0xff)       | ((a[offset+7*i+1] & 0x3f) << 8));
			    coeffs[4*i+1] = (char)(((a[offset+7*i+1] & 0xc0) >> 6) | ((a[offset+7*i+2] & 0xff) << 2) | ((a[offset+7*i+3] & 0x0f) << 10));
			    coeffs[4*i+2] = (char)(((a[offset+7*i+3] & 0xf0) >> 4) | ((a[offset+7*i+4] & 0xff) << 4) | ((a[offset+7*i+5] & 0x03) << 12));
			    coeffs[4*i+3] = (char)(((a[offset+7*i+5] & 0xfc) >> 2) | ((a[offset+7*i+6] & 0xff) << 6));
			}
		}

		public void tobytes(byte[] r, int offset)
		{
			int i;
			int t0,t1,t2,t3,m;
			int c;
			for (i = 0; i < PARAM_N/4; i++)
			{
			    t0 = barrett_reduce(coeffs[4*i+0]); //Make sure that coefficients have only 14 bits
			    t1 = barrett_reduce(coeffs[4*i+1]);
			    t2 = barrett_reduce(coeffs[4*i+2]);
			    t3 = barrett_reduce(coeffs[4*i+3]);
			
			    m = t0 - PARAM_Q;
			    c = m;
			    c >>= 15;
			    t0 = m ^ ((t0^m)&c); // <Make sure that coefficients are in [0,q]
			
			    m = t1 - PARAM_Q;
			    c = m;
			    c >>= 15;
			    t1 = m ^ ((t1^m)&c); // <Make sure that coefficients are in [0,q]
			
			    m = t2 - PARAM_Q;
			    c = m;
			    c >>= 15;
			    t2 = m ^ ((t2^m)&c); // <Make sure that coefficients are in [0,q]
			
			    m = t3 - PARAM_Q;
			    c = m;
			    c >>= 15;
			    t3 = m ^ ((t3^m)&c); // <Make sure that coefficients are in [0,q]
			
			    r[offset+7*i+0] = (byte)(t0 & 0xff);
			    r[offset+7*i+1] = (byte)((t0 >> 8) | (t1 << 6));
			    r[offset+7*i+2] = (byte)(t1 >> 2);
			    r[offset+7*i+3] = (byte)((t1 >> 10) | (t2 << 4));
			    r[offset+7*i+4] = (byte)(t2 >> 4);
			    r[offset+7*i+5] = (byte)((t2 >> 12) | (t3 << 2));
			    r[offset+7*i+6] = (byte)(t3 >> 6);
			}
		}

		public void getnoise(byte[] seed, byte nonce)
		{
		  byte[] buf = new byte [4*PARAM_N];
		  int /*t, d,*/ a, b;
		  int i/*,j*/;

		  try {
			  crypto_stream_chacha20(buf,0,4*PARAM_N,nonce,seed);
	
			  for(i=0;i<PARAM_N;i++)
			  {
				/*
				The original C reference code:
				
			    t = (buf[4*i] & 0xff) | (((buf[4*i+1]) & 0xff) << 8) | (((buf[4*i+2]) & 0xff) << 16) | (((buf[4*i+3]) & 0xff) << 24);
			    d = 0;
			    for(j=0;j<8;j++)
			      d += (t >>> j) & 0x01010101;
			    a = ((d >>> 8) & 0xff) + (d & 0xff);
			    b = (d >>> 24) + ((d >>> 16) & 0xff);
			    
			    What the above is doing is reading 32-bit words from buf and then
			    setting a and b to the number of 1 bits in the low and high 16 bits.
			    We instead use the following technique from "Bit Twiddling Hacks",
			    modified for 16-bit quantities:
			    
			    https://graphics.stanford.edu/~seander/bithacks.html#CountBitsSetParallel
			    */
				a = (buf[4*i] & 0xff) | (((buf[4*i+1]) & 0xff) << 8);
				a = a - ((a >> 1) & 0x5555);
				a = (a & 0x3333) + ((a >> 2) & 0x3333);
				a = ((a >> 4) + a) & 0x0F0F;
				a = ((a >> 8) + a) & 0x00FF;

				b = (buf[4*i+2] & 0xff) | (((buf[4*i+3]) & 0xff) << 8);
				b = b - ((b >> 1) & 0x5555);
				b = (b & 0x3333) + ((b >> 2) & 0x3333);
				b = ((b >> 4) + b) & 0x0F0F;
				b = ((b >> 8) + b) & 0x00FF;

				coeffs[i] = (char)(a + PARAM_Q - b);
			  }
		  } finally {
			  Arrays.fill(buf, (byte)0);
		  }
		}
		
		public void pointwise(Poly a, Poly b)
		{
		  int i;
		  int t;
		  for(i=0;i<PARAM_N;i++)
		  {
		    t       = montgomery_reduce(3186*b.coeffs[i]); /* t is now in Montgomery domain */
		    coeffs[i] = (char)montgomery_reduce(a.coeffs[i] * t); /* coeffs[i] is back in normal domain */
		  }
		}

		public void add(Poly a, Poly b)
		{
		  int i;
		  for(i=0;i<PARAM_N;i++)
		    coeffs[i] = (char)barrett_reduce(a.coeffs[i] + b.coeffs[i]);
		}

		public void ntt()
		{
		  mul_coefficients(coeffs, psis_bitrev_montgomery);
		  ntt_global(coeffs, omegas_montgomery);
		}

		public void invntt()
		{
		  bitrev_vector(coeffs);
		  ntt_global(coeffs, omegas_inv_montgomery);
		  mul_coefficients(coeffs, psis_inv_montgomery);
		}
	}

	/**
	 * Derives the public "a" value from a 32-byte seed.
	 *  
	 * @param coeffs The 1024 16-bit coefficients of "a" on exit.
	 * @param seed The 32-byte seed to use to generate "a".
	 * 
	 * The base class implementation is not constant-time but usually
	 * this doesn't matter for the public "a" value.  However, as
	 * described in the New Hope paper, non constant-time generation
	 * of "a" can be a problem in anonymity networks like Tor.
	 * 
	 * This function can be overridden in subclasses to provide a
	 * different method for generating "a".  The NewHopeTor class
	 * provides such an example.
	 * 
	 * Reference: https://cryptojedi.org/papers/newhope-20160803.pdf
	 */
	protected void uniform(char[] coeffs, byte[] seed)
	{
	  int pos=0, ctr=0;
	  int val;
	  long[] state = new long [25];
	  int nblocks=14;
	  byte[] buf = new byte [SHAKE128_RATE*nblocks];

	  try {
		  shake128_absorb(state, seed, 0, SEEDBYTES);

		  shake128_squeezeblocks(buf, 0, nblocks, state);

		  while(ctr < PARAM_N)
		  {
		    val = ((buf[pos] & 0xff) | ((buf[pos+1] & 0xff) << 8));
		    if(val < 5*PARAM_Q)
		      coeffs[ctr++] = (char)val;
		    pos += 2;
		    if(pos > SHAKE128_RATE*nblocks-2)
		    {
		      nblocks=1;
		      shake128_squeezeblocks(buf,0,nblocks,state);
		      pos = 0;
		    }
		  }
	  } finally {
		  Arrays.fill(state, 0);
		  Arrays.fill(buf, (byte)0);
	  }
	}

	// -------------- reduce.c --------------

	private static final int qinv = 12287; // -inverse_mod(p,2^18)
	private static final int rlog = 18;

	private static int montgomery_reduce(int a)
	{
	  int u;

	  u = (a * qinv);
	  u &= ((1<<rlog)-1);
	  u *= PARAM_Q;
	  a = a + u;
	  return a >>> 18;
	}

	private static int barrett_reduce(int a)
	{
	  int u;
	  a &= 0xffff;
	  u = (a * 5) >> 16;
	  u *= PARAM_Q;
	  a -= u;
	  return a & 0xffff;
	}
	
	// -------------- error_correction.c --------------

	private static int abs(int v)
	{
	  int mask = v >> 31;
	  return (v ^ mask) - mask;
	}

	private static int f(int[] v0, int v0offset, int[] v1, int v1offset, int x)
	{
	  int xit, t, r, b;

	  // Next 6 lines compute t = x/PARAM_Q;
	  b = x*2730;
	  t = b >> 25;
	  b = x - t*12289;
	  b = 12288 - b;
	  b >>= 31;
	  t -= b;

	  r = t & 1;
	  xit = (t>>1);
	  v0[v0offset] = xit+r; // v0 = round(x/(2*PARAM_Q))

	  t -= 1;
	  r = t & 1;
	  v1[v1offset] = (t>>1)+r;

	  return abs(x-((v0[v0offset])*2*PARAM_Q));
	}

	private static int g(int x)
	{
	  int t,c,b;

	  // Next 6 lines compute t = x/(4*PARAM_Q);
	  b = x*2730;
	  t = b >> 27;
	  b = x - t*49156;
	  b = 49155 - b;
	  b >>= 31;
	  t -= b;

	  c = t & 1;
	  t = (t >> 1) + c; // t = round(x/(8*PARAM_Q))

	  t *= 8*PARAM_Q;

	  return abs(t - x);
	}

	private static int LDDecode(int xi0, int xi1, int xi2, int xi3)
	{
	  int t;

	  t  = g(xi0);
	  t += g(xi1);
	  t += g(xi2);
	  t += g(xi3);

	  t -= 8*PARAM_Q;
	  t >>= 31;
	  return t&1;
	}

	private static void helprec(Poly c, Poly v, byte[] seed, byte nonce)
	{
	  int[] v0 = new int [8];
	  int v_tmp0,v_tmp1,v_tmp2,v_tmp3;
	  int k;
	  int rbit;
	  byte[] rand = new byte [32];
	  int i;

	  try {
		  crypto_stream_chacha20(rand,0,32,((long)nonce) << 56,seed);
	
		  for(i=0; i<256; i++)
		  {
		    rbit = (rand[i>>3] >> (i&7)) & 1;
	
		    k  = f(v0,0, v0,4, 8*v.coeffs[  0+i] + 4*rbit);
		    k += f(v0,1, v0,5, 8*v.coeffs[256+i] + 4*rbit);
		    k += f(v0,2, v0,6, 8*v.coeffs[512+i] + 4*rbit);
		    k += f(v0,3, v0,7, 8*v.coeffs[768+i] + 4*rbit);
	
		    k = (2*PARAM_Q-1-k) >> 31;
	
		    v_tmp0 = ((~k) & v0[0]) ^ (k & v0[4]);
		    v_tmp1 = ((~k) & v0[1]) ^ (k & v0[5]);
		    v_tmp2 = ((~k) & v0[2]) ^ (k & v0[6]);
		    v_tmp3 = ((~k) & v0[3]) ^ (k & v0[7]);
	
		    c.coeffs[  0+i] = (char)((v_tmp0 -   v_tmp3) & 3);
		    c.coeffs[256+i] = (char)((v_tmp1 -   v_tmp3) & 3);
		    c.coeffs[512+i] = (char)((v_tmp2 -   v_tmp3) & 3);
		    c.coeffs[768+i] = (char)((   -k  + 2*v_tmp3) & 3);
		  }
	  } finally {
		  Arrays.fill(v0, 0);
		  Arrays.fill(rand, (byte)0);
	  }
	}

	private static void rec(byte[] key, Poly v, Poly c)
	{
	  int i;
	  int tmp0,tmp1,tmp2,tmp3;

	  for(i=0;i<32;i++)
	    key[i] = 0;

	  for(i=0; i<256; i++)
	  {
		char c768 = c.coeffs[768+i];
	    tmp0 = 16*PARAM_Q + 8*(int)v.coeffs[  0+i] - PARAM_Q * (2*c.coeffs[  0+i]+c768);
	    tmp1 = 16*PARAM_Q + 8*(int)v.coeffs[256+i] - PARAM_Q * (2*c.coeffs[256+i]+c768);
	    tmp2 = 16*PARAM_Q + 8*(int)v.coeffs[512+i] - PARAM_Q * (2*c.coeffs[512+i]+c768);
	    tmp3 = 16*PARAM_Q + 8*(int)v.coeffs[768+i] - PARAM_Q * (                  c768);

	    key[i>>3] |= LDDecode(tmp0, tmp1, tmp2, tmp3) << (i & 7);
	  }
	}
	
	// -------------- ntt.c --------------

	private static final int bitrev_table_combined[/*496*/] = {
		524289,262146,786435,131076,655365,393222,917511,65544,
		589833,327690,851979,196620,720909,458766,983055,32784,
		557073,294930,819219,163860,688149,426006,950295,98328,
		622617,360474,884763,229404,753693,491550,1015839,540705,
		278562,802851,147492,671781,409638,933927,81960,606249,
		344106,868395,213036,737325,475182,999471,573489,311346,
		835635,180276,704565,442422,966711,114744,639033,376890,
		901179,245820,770109,507966,1032255,532545,270402,794691,
		139332,663621,401478,925767,598089,335946,860235,204876,
		729165,467022,991311,565329,303186,827475,172116,696405,
		434262,958551,106584,630873,368730,893019,237660,761949,
		499806,1024095,548961,286818,811107,155748,680037,417894,
		942183,614505,352362,876651,221292,745581,483438,1007727,
		581745,319602,843891,188532,712821,450678,974967,647289,
		385146,909435,254076,778365,516222,1040511,528513,266370,
		790659,659589,397446,921735,594057,331914,856203,200844,
		725133,462990,987279,561297,299154,823443,168084,692373,
		430230,954519,626841,364698,888987,233628,757917,495774,
		1020063,544929,282786,807075,676005,413862,938151,610473,
		348330,872619,217260,741549,479406,1003695,577713,315570,
		839859,708789,446646,970935,643257,381114,905403,250044,
		774333,512190,1036479,536769,274626,798915,667845,405702,
		929991,602313,340170,864459,733389,471246,995535,569553,
		307410,831699,700629,438486,962775,635097,372954,897243,
		241884,766173,504030,1028319,553185,291042,815331,684261,
		422118,946407,618729,356586,880875,749805,487662,1011951,
		585969,323826,848115,717045,454902,979191,651513,389370,
		913659,782589,520446,1044735,526593,788739,657669,395526,
		919815,592137,329994,854283,723213,461070,985359,559377,
		297234,821523,690453,428310,952599,624921,362778,887067,
		755997,493854,1018143,543009,805155,674085,411942,936231,
		608553,346410,870699,739629,477486,1001775,575793,837939,
		706869,444726,969015,641337,379194,903483,772413,510270,
		1034559,534849,796995,665925,403782,928071,600393,862539,
		731469,469326,993615,567633,829779,698709,436566,960855,
		633177,371034,895323,764253,502110,1026399,551265,813411,
		682341,420198,944487,616809,878955,747885,485742,1010031,
		584049,846195,715125,452982,977271,649593,911739,780669,
		518526,1042815,530817,792963,661893,924039,596361,858507,
		727437,465294,989583,563601,825747,694677,432534,956823,
		629145,891291,760221,498078,1022367,547233,809379,678309,
		940455,612777,874923,743853,481710,1005999,580017,842163,
		711093,973239,645561,907707,776637,514494,1038783,539073,
		801219,670149,932295,604617,866763,735693,997839,571857,
		834003,702933,965079,637401,899547,768477,506334,1030623,
		555489,817635,686565,948711,621033,883179,752109,1014255,
		588273,850419,719349,981495,653817,915963,784893,1047039,
		787971,656901,919047,591369,853515,722445,984591,558609,
		820755,689685,951831,624153,886299,755229,1017375,804387,
		673317,935463,607785,869931,738861,1001007,837171,706101,
		968247,640569,902715,771645,1033791,796227,665157,927303,
		861771,730701,992847,829011,697941,960087,632409,894555,
		763485,1025631,812643,681573,943719,878187,747117,1009263,
		845427,714357,976503,910971,779901,1042047,792195,923271,
		857739,726669,988815,824979,693909,956055,890523,759453,
		1021599,808611,939687,874155,743085,1005231,841395,972471,
		906939,775869,1038015,800451,931527,865995,997071,833235,
		964311,898779,767709,1029855,816867,947943,882411,1013487,
		849651,980727,915195,1046271,921351,855819,986895,823059,
		954135,888603,1019679,937767,872235,1003311,970551,905019,
		1036095,929607,995151,962391,896859,1027935,946023,1011567,
		978807,1044351,991119,958359,1023903,1007535,1040319,1032159
	};

	// Modified version of bitrev_vector() from the C reference code
	// that reduces the number of array bounds checks on the bitrev_table
	// from 1024 to 496.  The values in the combined table are encoded
	// as (i + (r * PARAM_N)) where i and r are the indices to swap.
	// The pseudo-code to generate this combined table is:
    //     p = 0;
    //     for (i = 0; i < PARAM_N; i++) {
    //         r = bitrev_table[i];
    //         if (i < r)
    //             bitrev_table_combined[p++] = i + (r * PARAM_N);
    //     }
	private static void bitrev_vector(char[] poly)
	{
	    int i,r,p;
	    char tmp;

	    for(p = 0; p < 496; ++p)
	    {
	    	int indices = bitrev_table_combined[p];
	    	i = indices & 0x03FF;
	    	r = indices >> 10;
        	tmp = poly[i];
        	poly[i] = poly[r];
        	poly[r] = tmp;
	    }
	}

	private static void mul_coefficients(char[] poly, char[] factors)
	{
	    int i;

	    for(i = 0; i < PARAM_N; i++)
	      poly[i] = (char)montgomery_reduce((poly[i] * factors[i]));
	}
	
	/* GS_bo_to_no; omegas need to be in Montgomery domain */
	private static void ntt_global(char[] a, char[] omega)
	{
	  int i, start, j, jTwiddle, distance;
	  char temp, W;


	  for(i=0;i<10;i+=2)
	  {
	    // Even level
	    distance = (1<<i);
	    for(start = 0; start < distance;start++)
	    {
	      jTwiddle = 0;
	      for(j=start;j<PARAM_N-1;j+=2*distance)
	      {
	        W = omega[jTwiddle++];
	        temp = a[j];
	        a[j] = (char)(temp + a[j + distance]); // Omit reduction (be lazy)
	        a[j + distance] = (char)montgomery_reduce((W * ((int)temp + 3*PARAM_Q - a[j + distance])));
	      }
	    }

	    // Odd level
	    distance <<= 1;
	    for(start = 0; start < distance;start++)
	    {
	      jTwiddle = 0;
	      for(j=start;j<PARAM_N-1;j+=2*distance)
	      {
	        W = omega[jTwiddle++];
	        temp = a[j];
	        a[j] = (char)barrett_reduce((temp + a[j + distance]));
	        a[j + distance] = (char)montgomery_reduce((W * ((int)temp + 3*PARAM_Q - a[j + distance])));
	      }
	    }
	  }
	}

	// -------------- fips202.c --------------

	/* Based on the public domain implementation in
	 * crypto_hash/keccakc512/simple/ from http://bench.cr.yp.to/supercop.html
	 * by Ronny Van Keer 
	 * and the public domain "TweetFips202" implementation
	 * from https://twitter.com/tweetfips202
	 * by Gilles Van Assche, Daniel J. Bernstein, and Peter Schwabe */

	private static long ROL(long a, int offset)
	{
		return (a << offset) ^ (a >>> (64 - offset));
	}

	private static long load64(byte[] x, int offset)
	{
	  long r = 0;

	  for (int i = 0; i < 8; ++i) {
	    r |= ((long)(x[offset+i] & 0xff)) << (8 * i);
	  }
	  return r;
	}

	private static void store64(byte[] x, int offset, long u)
	{
	  int i;

	  for(i=0; i<8; ++i) {
	    x[offset+i] = (byte)u;
	    u >>= 8;
	  }
	}

	private static final long[] KeccakF_RoundConstants =
		{
		    0x0000000000000001L,
		    0x0000000000008082L,
		    0x800000000000808aL,
		    0x8000000080008000L,
		    0x000000000000808bL,
		    0x0000000080000001L,
		    0x8000000080008081L,
		    0x8000000000008009L,
		    0x000000000000008aL,
		    0x0000000000000088L,
		    0x0000000080008009L,
		    0x000000008000000aL,
		    0x000000008000808bL,
		    0x800000000000008bL,
		    0x8000000000008089L,
		    0x8000000000008003L,
		    0x8000000000008002L,
		    0x8000000000000080L,
		    0x000000000000800aL,
		    0x800000008000000aL,
		    0x8000000080008081L,
		    0x8000000000008080L,
		    0x0000000080000001L,
		    0x8000000080008008L
		};

	
	private static void KeccakF1600_StatePermute(long[] state)
	{
	    int round;

        long Aba, Abe, Abi, Abo, Abu;
        long Aga, Age, Agi, Ago, Agu;
        long Aka, Ake, Aki, Ako, Aku;
        long Ama, Ame, Ami, Amo, Amu;
        long Asa, Ase, Asi, Aso, Asu;
        long BCa, BCe, BCi, BCo, BCu;
        long Da, De, Di, Do, Du;
        long Eba, Ebe, Ebi, Ebo, Ebu;
        long Ega, Ege, Egi, Ego, Egu;
        long Eka, Eke, Eki, Eko, Eku;
        long Ema, Eme, Emi, Emo, Emu;
        long Esa, Ese, Esi, Eso, Esu;

        //copyFromState(A, state)
        Aba = state[ 0];
        Abe = state[ 1];
        Abi = state[ 2];
        Abo = state[ 3];
        Abu = state[ 4];
        Aga = state[ 5];
        Age = state[ 6];
        Agi = state[ 7];
        Ago = state[ 8];
        Agu = state[ 9];
        Aka = state[10];
        Ake = state[11];
        Aki = state[12];
        Ako = state[13];
        Aku = state[14];
        Ama = state[15];
        Ame = state[16];
        Ami = state[17];
        Amo = state[18];
        Amu = state[19];
        Asa = state[20];
        Ase = state[21];
        Asi = state[22];
        Aso = state[23];
        Asu = state[24];

        for( round = 0; round < 24; round += 2 )
        {
            //    prepareTheta
            BCa = Aba^Aga^Aka^Ama^Asa;
            BCe = Abe^Age^Ake^Ame^Ase;
            BCi = Abi^Agi^Aki^Ami^Asi;
            BCo = Abo^Ago^Ako^Amo^Aso;
            BCu = Abu^Agu^Aku^Amu^Asu;

            //thetaRhoPiChiIotaPrepareTheta(round  , A, E)
            Da = BCu^ROL(BCe, 1);
            De = BCa^ROL(BCi, 1);
            Di = BCe^ROL(BCo, 1);
            Do = BCi^ROL(BCu, 1);
            Du = BCo^ROL(BCa, 1);

            Aba ^= Da;
            BCa = Aba;
            Age ^= De;
            BCe = ROL(Age, 44);
            Aki ^= Di;
            BCi = ROL(Aki, 43);
            Amo ^= Do;
            BCo = ROL(Amo, 21);
            Asu ^= Du;
            BCu = ROL(Asu, 14);
            Eba =   BCa ^((~BCe)&  BCi );
            Eba ^= KeccakF_RoundConstants[round];
            Ebe =   BCe ^((~BCi)&  BCo );
            Ebi =   BCi ^((~BCo)&  BCu );
            Ebo =   BCo ^((~BCu)&  BCa );
            Ebu =   BCu ^((~BCa)&  BCe );

            Abo ^= Do;
            BCa = ROL(Abo, 28);
            Agu ^= Du;
            BCe = ROL(Agu, 20);
            Aka ^= Da;
            BCi = ROL(Aka,  3);
            Ame ^= De;
            BCo = ROL(Ame, 45);
            Asi ^= Di;
            BCu = ROL(Asi, 61);
            Ega =   BCa ^((~BCe)&  BCi );
            Ege =   BCe ^((~BCi)&  BCo );
            Egi =   BCi ^((~BCo)&  BCu );
            Ego =   BCo ^((~BCu)&  BCa );
            Egu =   BCu ^((~BCa)&  BCe );

            Abe ^= De;
            BCa = ROL(Abe,  1);
            Agi ^= Di;
            BCe = ROL(Agi,  6);
            Ako ^= Do;
            BCi = ROL(Ako, 25);
            Amu ^= Du;
            BCo = ROL(Amu,  8);
            Asa ^= Da;
            BCu = ROL(Asa, 18);
            Eka =   BCa ^((~BCe)&  BCi );
            Eke =   BCe ^((~BCi)&  BCo );
            Eki =   BCi ^((~BCo)&  BCu );
            Eko =   BCo ^((~BCu)&  BCa );
            Eku =   BCu ^((~BCa)&  BCe );

            Abu ^= Du;
            BCa = ROL(Abu, 27);
            Aga ^= Da;
            BCe = ROL(Aga, 36);
            Ake ^= De;
            BCi = ROL(Ake, 10);
            Ami ^= Di;
            BCo = ROL(Ami, 15);
            Aso ^= Do;
            BCu = ROL(Aso, 56);
            Ema =   BCa ^((~BCe)&  BCi );
            Eme =   BCe ^((~BCi)&  BCo );
            Emi =   BCi ^((~BCo)&  BCu );
            Emo =   BCo ^((~BCu)&  BCa );
            Emu =   BCu ^((~BCa)&  BCe );

            Abi ^= Di;
            BCa = ROL(Abi, 62);
            Ago ^= Do;
            BCe = ROL(Ago, 55);
            Aku ^= Du;
            BCi = ROL(Aku, 39);
            Ama ^= Da;
            BCo = ROL(Ama, 41);
            Ase ^= De;
            BCu = ROL(Ase,  2);
            Esa =   BCa ^((~BCe)&  BCi );
            Ese =   BCe ^((~BCi)&  BCo );
            Esi =   BCi ^((~BCo)&  BCu );
            Eso =   BCo ^((~BCu)&  BCa );
            Esu =   BCu ^((~BCa)&  BCe );

            //    prepareTheta
            BCa = Eba^Ega^Eka^Ema^Esa;
            BCe = Ebe^Ege^Eke^Eme^Ese;
            BCi = Ebi^Egi^Eki^Emi^Esi;
            BCo = Ebo^Ego^Eko^Emo^Eso;
            BCu = Ebu^Egu^Eku^Emu^Esu;

            //thetaRhoPiChiIotaPrepareTheta(round+1, E, A)
            Da = BCu^ROL(BCe, 1);
            De = BCa^ROL(BCi, 1);
            Di = BCe^ROL(BCo, 1);
            Do = BCi^ROL(BCu, 1);
            Du = BCo^ROL(BCa, 1);

            Eba ^= Da;
            BCa = Eba;
            Ege ^= De;
            BCe = ROL(Ege, 44);
            Eki ^= Di;
            BCi = ROL(Eki, 43);
            Emo ^= Do;
            BCo = ROL(Emo, 21);
            Esu ^= Du;
            BCu = ROL(Esu, 14);
            Aba =   BCa ^((~BCe)&  BCi );
            Aba ^= KeccakF_RoundConstants[round+1];
            Abe =   BCe ^((~BCi)&  BCo );
            Abi =   BCi ^((~BCo)&  BCu );
            Abo =   BCo ^((~BCu)&  BCa );
            Abu =   BCu ^((~BCa)&  BCe );

            Ebo ^= Do;
            BCa = ROL(Ebo, 28);
            Egu ^= Du;
            BCe = ROL(Egu, 20);
            Eka ^= Da;
            BCi = ROL(Eka, 3);
            Eme ^= De;
            BCo = ROL(Eme, 45);
            Esi ^= Di;
            BCu = ROL(Esi, 61);
            Aga =   BCa ^((~BCe)&  BCi );
            Age =   BCe ^((~BCi)&  BCo );
            Agi =   BCi ^((~BCo)&  BCu );
            Ago =   BCo ^((~BCu)&  BCa );
            Agu =   BCu ^((~BCa)&  BCe );

            Ebe ^= De;
            BCa = ROL(Ebe, 1);
            Egi ^= Di;
            BCe = ROL(Egi, 6);
            Eko ^= Do;
            BCi = ROL(Eko, 25);
            Emu ^= Du;
            BCo = ROL(Emu, 8);
            Esa ^= Da;
            BCu = ROL(Esa, 18);
            Aka =   BCa ^((~BCe)&  BCi );
            Ake =   BCe ^((~BCi)&  BCo );
            Aki =   BCi ^((~BCo)&  BCu );
            Ako =   BCo ^((~BCu)&  BCa );
            Aku =   BCu ^((~BCa)&  BCe );

            Ebu ^= Du;
            BCa = ROL(Ebu, 27);
            Ega ^= Da;
            BCe = ROL(Ega, 36);
            Eke ^= De;
            BCi = ROL(Eke, 10);
            Emi ^= Di;
            BCo = ROL(Emi, 15);
            Eso ^= Do;
            BCu = ROL(Eso, 56);
            Ama =   BCa ^((~BCe)&  BCi );
            Ame =   BCe ^((~BCi)&  BCo );
            Ami =   BCi ^((~BCo)&  BCu );
            Amo =   BCo ^((~BCu)&  BCa );
            Amu =   BCu ^((~BCa)&  BCe );

            Ebi ^= Di;
            BCa = ROL(Ebi, 62);
            Ego ^= Do;
            BCe = ROL(Ego, 55);
            Eku ^= Du;
            BCi = ROL(Eku, 39);
            Ema ^= Da;
            BCo = ROL(Ema, 41);
            Ese ^= De;
            BCu = ROL(Ese, 2);
            Asa =   BCa ^((~BCe)&  BCi );
            Ase =   BCe ^((~BCi)&  BCo );
            Asi =   BCi ^((~BCo)&  BCu );
            Aso =   BCo ^((~BCu)&  BCa );
            Asu =   BCu ^((~BCa)&  BCe );
        }

        //copyToState(state, A)
        state[ 0] = Aba;
        state[ 1] = Abe;
        state[ 2] = Abi;
        state[ 3] = Abo;
        state[ 4] = Abu;
        state[ 5] = Aga;
        state[ 6] = Age;
        state[ 7] = Agi;
        state[ 8] = Ago;
        state[ 9] = Agu;
        state[10] = Aka;
        state[11] = Ake;
        state[12] = Aki;
        state[13] = Ako;
        state[14] = Aku;
        state[15] = Ama;
        state[16] = Ame;
        state[17] = Ami;
        state[18] = Amo;
        state[19] = Amu;
        state[20] = Asa;
        state[21] = Ase;
        state[22] = Asi;
        state[23] = Aso;
        state[24] = Asu;
	}

	private static void keccak_absorb(long[] s, int r, byte[] m, int offset, int mlen, byte p)
	{
	  int i;
	  byte[] t = new byte [200];

	  try {
		  for (i = 0; i < 25; ++i)
		    s[i] = 0;

		  while (mlen >= r)
		  {
		    for (i = 0; i < r / 8; ++i)
		      s[i] ^= load64(m, offset + 8 * i);

		    KeccakF1600_StatePermute(s);
		    mlen -= r;
		    offset += r;
		  }

		  for (i = 0; i < r; ++i)
		    t[i] = 0;
		  for (i = 0; i < mlen; ++i)
		    t[i] = m[offset + i];
		  t[i] = p;
		  t[r - 1] |= 128;
		  for (i = 0; i < r / 8; ++i)
		    s[i] ^= load64(t, 8 * i);
	  } finally {
		  Arrays.fill(t, (byte)0);
	  }
	}

	private static void keccak_squeezeblocks(byte[] h, int offset, int nblocks, long [] s, int r)
	{
	  int i;
	  while(nblocks > 0)
	  {
	    KeccakF1600_StatePermute(s);
	    for(i=0;i<(r>>3);i++)
	    {
	      store64(h, offset+8*i, s[i]);
	    }
	    offset += r;
	    nblocks--;
	  }
	}

	static final int SHAKE128_RATE = 168;
	
	static void shake128_absorb(long[] s, byte[] input, int inputOffset, int inputByteLen)
	{
	  keccak_absorb(s, SHAKE128_RATE, input, inputOffset, inputByteLen, (byte)0x1F);
	}

	static void shake128_squeezeblocks(byte[] output, int outputOffset, int nblocks, long[] s)
	{
	  keccak_squeezeblocks(output, outputOffset, nblocks, s, SHAKE128_RATE);
	}

	private static final int SHA3_256_RATE = 136;
	
	private static void sha3256(byte[] output, int outputOffset, byte[] input, int inputOffset, int inputByteLen)
	{
	  long[] s = new long [25];
	  byte[] t = new byte [SHA3_256_RATE];
	  int i;

	  try {
		  keccak_absorb(s, SHA3_256_RATE, input, inputOffset, inputByteLen, (byte)0x06);
		  keccak_squeezeblocks(t, 0, 1, s, SHA3_256_RATE);
		  for(i=0;i<32;i++)
		    output[i] = t[i];
	  } finally {
		  Arrays.fill(s, 0);
		  Arrays.fill(t, (byte)0);
	  }
	}

	// -------------- crypto_stream_chacha20.c --------------

	/* Based on the public domain implemntation in
	 * crypto_stream/chacha20/e/ref from http://bench.cr.yp.to/supercop.html
	 * by Daniel J. Bernstein */

	private static int load_littleendian(byte[] x, int offset)
	{
	  return
	      (int) (x[offset + 0] & 0xff)
	  | (((int) (x[offset + 1] & 0xff)) << 8)
	  | (((int) (x[offset + 2] & 0xff)) << 16)
	  | (((int) (x[offset + 3] & 0xff)) << 24);
	}

	private static void store_littleendian(byte[] x, int offset, int u)
	{
	  x[offset + 0] = (byte)u; u >>= 8;
	  x[offset + 1] = (byte)u; u >>= 8;
	  x[offset + 2] = (byte)u; u >>= 8;
	  x[offset + 3] = (byte)u;
	}
	
	// Note: This version is limited to a maximum of 2^32 blocks or 2^38 bytes
	// because the block number counter is 32-bit instead of 64-bit.  This isn't
	// a problem for New Hope because the maximum required output is 4096 bytes.
	private static void crypto_core_chacha20(byte[] out, int outOffset, long nonce, int blknum, byte[] k)
	{
	  int x0, x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15;
	  int j0, j1, j2, j3, j4, j5, j6, j7, j8, j9, j10, j11, j12, j13, j14, j15;
	  int i;

	  j0  = x0  = 0x61707865;				// "expa"
	  j1  = x1  = 0x3320646e;				// "nd 3"
	  j2  = x2  = 0x79622d32;				// "2-by"
	  j3  = x3  = 0x6b206574;				// "te k"
	  j4  = x4  = load_littleendian(k,  0);
	  j5  = x5  = load_littleendian(k,  4);
	  j6  = x6  = load_littleendian(k,  8);
	  j7  = x7  = load_littleendian(k, 12);
	  j8  = x8  = load_littleendian(k, 16);
	  j9  = x9  = load_littleendian(k, 20);
	  j10 = x10 = load_littleendian(k, 24);
	  j11 = x11 = load_littleendian(k, 28);
	  j12 = x12 = blknum;
	  j13 = x13 = 0;
	  j14 = x14 = (int)nonce;
	  j15 = x15 = (int)(nonce >>> 32);

	  for (i = 20;i > 0;i -= 2) {
		  x0  += x4 ; x12 ^= x0 ; x12 = (x12 << 16) | (x12 >>> 16);
		  x8  += x12; x4  ^= x8 ; x4  = (x4  << 12) | (x4  >>> 20);
		  x0  += x4 ; x12 ^= x0 ; x12 = (x12 <<  8) | (x12 >>> 24);
		  x8  += x12; x4  ^= x8 ; x4  = (x4  <<  7) | (x4  >>> 25);
		  x1  += x5 ; x13 ^= x1 ; x13 = (x13 << 16) | (x13 >>> 16);
		  x9  += x13; x5  ^= x9 ; x5  = (x5  << 12) | (x5  >>> 20);
		  x1  += x5 ; x13 ^= x1 ; x13 = (x13 <<  8) | (x13 >>> 24);
		  x9  += x13; x5  ^= x9 ; x5  = (x5  <<  7) | (x5  >>> 25);
		  x2  += x6 ; x14 ^= x2 ; x14 = (x14 << 16) | (x14 >>> 16);
		  x10 += x14; x6  ^= x10; x6  = (x6  << 12) | (x6  >>> 20);
		  x2  += x6 ; x14 ^= x2 ; x14 = (x14 <<  8) | (x14 >>> 24);
		  x10 += x14; x6  ^= x10; x6  = (x6  <<  7) | (x6  >>> 25);
		  x3  += x7 ; x15 ^= x3 ; x15 = (x15 << 16) | (x15 >>> 16);
		  x11 += x15; x7  ^= x11; x7  = (x7  << 12) | (x7  >>> 20);
		  x3  += x7 ; x15 ^= x3 ; x15 = (x15 <<  8) | (x15 >>> 24);
		  x11 += x15; x7  ^= x11; x7  = (x7  <<  7) | (x7  >>> 25);
		  x0  += x5 ; x15 ^= x0 ; x15 = (x15 << 16) | (x15 >>> 16);
		  x10 += x15; x5  ^= x10; x5  = (x5  << 12) | (x5  >>> 20);
		  x0  += x5 ; x15 ^= x0 ; x15 = (x15 <<  8) | (x15 >>> 24);
		  x10 += x15; x5  ^= x10; x5  = (x5  <<  7) | (x5  >>> 25);
		  x1  += x6 ; x12 ^= x1 ; x12 = (x12 << 16) | (x12 >>> 16);
		  x11 += x12; x6  ^= x11; x6  = (x6  << 12) | (x6  >>> 20);
		  x1  += x6 ; x12 ^= x1 ; x12 = (x12 <<  8) | (x12 >>> 24);
		  x11 += x12; x6  ^= x11; x6  = (x6  <<  7) | (x6  >>> 25);
		  x2  += x7 ; x13 ^= x2 ; x13 = (x13 << 16) | (x13 >>> 16);
		  x8  += x13; x7  ^= x8 ; x7  = (x7  << 12) | (x7  >>> 20);
		  x2  += x7 ; x13 ^= x2 ; x13 = (x13 <<  8) | (x13 >>> 24);
		  x8  += x13; x7  ^= x8 ; x7  = (x7  <<  7) | (x7  >>> 25);
		  x3  += x4 ; x14 ^= x3 ; x14 = (x14 << 16) | (x14 >>> 16);
		  x9  += x14; x4  ^= x9 ; x4  = (x4  << 12) | (x4  >>> 20);
		  x3  += x4 ; x14 ^= x3 ; x14 = (x14 <<  8) | (x14 >>> 24);
		  x9  += x14; x4  ^= x9 ; x4  = (x4  <<  7) | (x4  >>> 25);
	  }

	  x0 += j0;
	  x1 += j1;
	  x2 += j2;
	  x3 += j3;
	  x4 += j4;
	  x5 += j5;
	  x6 += j6;
	  x7 += j7;
	  x8 += j8;
	  x9 += j9;
	  x10 += j10;
	  x11 += j11;
	  x12 += j12;
	  x13 += j13;
	  x14 += j14;
	  x15 += j15;

	  store_littleendian(out, outOffset + 0,x0);
	  store_littleendian(out, outOffset + 4,x1);
	  store_littleendian(out, outOffset + 8,x2);
	  store_littleendian(out, outOffset + 12,x3);
	  store_littleendian(out, outOffset + 16,x4);
	  store_littleendian(out, outOffset + 20,x5);
	  store_littleendian(out, outOffset + 24,x6);
	  store_littleendian(out, outOffset + 28,x7);
	  store_littleendian(out, outOffset + 32,x8);
	  store_littleendian(out, outOffset + 36,x9);
	  store_littleendian(out, outOffset + 40,x10);
	  store_littleendian(out, outOffset + 44,x11);
	  store_littleendian(out, outOffset + 48,x12);
	  store_littleendian(out, outOffset + 52,x13);
	  store_littleendian(out, outOffset + 56,x14);
	  store_littleendian(out, outOffset + 60,x15);
	}

	private static void crypto_stream_chacha20(byte[] c, int coffset, int clen, long n, byte[] k)
	{
	  int blknum = 0;

	  if (clen <= 0) return;

	  while (clen >= 64) {
	    crypto_core_chacha20(c,coffset,n,blknum,k);
	    ++blknum;
	    clen -= 64;
	    coffset += 64;
	  }

	  if (clen != 0) {
		byte[] block = new byte [64];
		try {
		    crypto_core_chacha20(block,0,n,blknum,k);
		    for (int i = 0;i < clen;++i) c[coffset+i] = block[i];
		} finally {
			Arrays.fill(block, (byte)0);
		}
	  }
	}

	// -------------- precomp.c --------------

	private static final char[/*PARAM_N/2*/] omegas_montgomery = {
		4075,6974,7373,7965,3262,5079,522,2169,6364,1018,1041,8775,2344,
		11011,5574,1973,4536,1050,6844,3860,3818,6118,2683,1190,4789,7822,
		7540,6752,5456,4449,3789,12142,11973,382,3988,468,6843,5339,6196,
		3710,11316,1254,5435,10930,3998,10256,10367,3879,11889,1728,6137,
		4948,5862,6136,3643,6874,8724,654,10302,1702,7083,6760,56,3199,9987,
		605,11785,8076,5594,9260,6403,4782,6212,4624,9026,8689,4080,11868,
		6221,3602,975,8077,8851,9445,5681,3477,1105,142,241,12231,1003,
		3532,5009,1956,6008,11404,7377,2049,10968,12097,7591,5057,3445,
		4780,2920,7048,3127,8120,11279,6821,11502,8807,12138,2127,2839,
		3957,431,1579,6383,9784,5874,677,3336,6234,2766,1323,9115,12237,
		2031,6956,6413,2281,3969,3991,12133,9522,4737,10996,4774,5429,11871,
		3772,453,5908,2882,1805,2051,1954,11713,3963,2447,6142,8174,3030,
		1843,2361,12071,2908,3529,3434,3202,7796,2057,5369,11939,1512,6906,
		10474,11026,49,10806,5915,1489,9789,5942,10706,10431,7535,426,8974,
		3757,10314,9364,347,5868,9551,9634,6554,10596,9280,11566,174,2948,
		2503,6507,10723,11606,2459,64,3656,8455,5257,5919,7856,1747,9166,
		5486,9235,6065,835,3570,4240,11580,4046,10970,9139,1058,8210,11848,
		922,7967,1958,10211,1112,3728,4049,11130,5990,1404,325,948,11143,
		6190,295,11637,5766,8212,8273,2919,8527,6119,6992,8333,1360,2555,
		6167,1200,7105,7991,3329,9597,12121,5106,5961,10695,10327,3051,9923,
		4896,9326,81,3091,1000,7969,4611,726,1853,12149,4255,11112,2768,
		10654,1062,2294,3553,4805,2747,4846,8577,9154,1170,2319,790,11334,
		9275,9088,1326,5086,9094,6429,11077,10643,3504,3542,8668,9744,1479,
		1,8246,7143,11567,10984,4134,5736,4978,10938,5777,8961,4591,5728,
		6461,5023,9650,7468,949,9664,2975,11726,2744,9283,10092,5067,12171,
		2476,3748,11336,6522,827,9452,5374,12159,7935,3296,3949,9893,4452,
		10908,2525,3584,8112,8011,10616,4989,6958,11809,9447,12280,1022,
		11950,9821,11745,5791,5092,2089,9005,2881,3289,2013,9048,729,7901,
		1260,5755,4632,11955,2426,10593,1428,4890,5911,3932,9558,8830,3637,
		5542,145,5179,8595,3707,10530,355,3382,4231,9741,1207,9041,7012,1168,
		10146,11224,4645,11885,10911,10377,435,7952,4096,493,9908,6845,6039,
		2422,2187,9723,8643,9852,9302,6022,7278,1002,4284,5088,1607,7313,
		875,8509,9430,1045,2481,5012,7428,354,6591,9377,11847,2401,1067,
		7188,11516,390,8511,8456,7270,545,8585,9611,12047,1537,4143,4714,
		4885,1017,5084,1632,3066,27,1440,8526,9273,12046,11618,9289,3400,
		9890,3136,7098,8758,11813,7384,3985,11869,6730,10745,10111,2249,
		4048,2884,11136,2126,1630,9103,5407,2686,9042,2969,8311,9424,
		9919,8779,5332,10626,1777,4654,10863,7351,3636,9585,5291,8374,
		2166,4919,12176,9140,12129,7852,12286,4895,10805,2780,5195,2305,
		7247,9644,4053,10600,3364,3271,4057,4414,9442,7917,2174
	};

	private static final char[/*PARAM_N/2*/] omegas_inv_montgomery = {
		4075,5315,4324,4916,10120,11767,7210,9027,10316,6715,1278,9945,
		3514,11248,11271,5925,147,8500,7840,6833,5537,4749,4467,7500,11099,
		9606,6171,8471,8429,5445,11239,7753,9090,12233,5529,5206,10587,
		1987,11635,3565,5415,8646,6153,6427,7341,6152,10561,400,8410,1922,
		2033,8291,1359,6854,11035,973,8579,6093,6950,5446,11821,8301,11907,
		316,52,3174,10966,9523,6055,8953,11612,6415,2505,5906,10710,11858,
		8332,9450,10162,151,3482,787,5468,1010,4169,9162,5241,9369,7509,
		8844,7232,4698,192,1321,10240,4912,885,6281,10333,7280,8757,11286,
		58,12048,12147,11184,8812,6608,2844,3438,4212,11314,8687,6068,421,
		8209,3600,3263,7665,6077,7507,5886,3029,6695,4213,504,11684,2302,
		1962,1594,6328,7183,168,2692,8960,4298,5184,11089,6122,9734,10929,
		3956,5297,6170,3762,9370,4016,4077,6523,652,11994,6099,1146,11341,
		11964,10885,6299,1159,8240,8561,11177,2078,10331,4322,11367,441,
		4079,11231,3150,1319,8243,709,8049,8719,11454,6224,3054,6803,3123,
		10542,4433,6370,7032,3834,8633,12225,9830,683,1566,5782,9786,9341,
		12115,723,3009,1693,5735,2655,2738,6421,11942,2925,1975,8532,3315,
		11863,4754,1858,1583,6347,2500,10800,6374,1483,12240,1263,1815,
		5383,10777,350,6920,10232,4493,9087,8855,8760,9381,218,9928,10446,
		9259,4115,6147,9842,8326,576,10335,10238,10484,9407,6381,11836,8517,
		418,6860,7515,1293,7552,2767,156,8298,8320,10008,5876,5333,10258,
		10115,4372,2847,7875,8232,9018,8925,1689,8236,2645,5042,9984,7094,
		9509,1484,7394,3,4437,160,3149,113,7370,10123,3915,6998,2704,8653,
		4938,1426,7635,10512,1663,6957,3510,2370,2865,3978,9320,3247,9603,
		6882,3186,10659,10163,1153,9405,8241,10040,2178,1544,5559,420,8304,
		4905,476,3531,5191,9153,2399,8889,3000,671,243,3016,3763,10849,12262,
		9223,10657,7205,11272,7404,7575,8146,10752,242,2678,3704,11744,
		5019,3833,3778,11899,773,5101,11222,9888,442,2912,5698,11935,4861,
		7277,9808,11244,2859,3780,11414,4976,10682,7201,8005,11287,5011,
		6267,2987,2437,3646,2566,10102,9867,6250,5444,2381,11796,8193,4337,
		11854,1912,1378,404,7644,1065,2143,11121,5277,3248,11082,2548,8058,
		8907,11934,1759,8582,3694,7110,12144,6747,8652,3459,2731,8357,6378,
		7399,10861,1696,9863,334,7657,6534,11029,4388,11560,3241,10276,9000,
		9408,3284,10200,7197,6498,544,2468,339,11267,9,2842,480,5331,7300,
		1673,4278,4177,8705,9764,1381,7837,2396,8340,8993,4354,130,6915,
		2837,11462,5767,953,8541,9813,118,7222,2197,3006,9545,563,9314,
		2625,11340,4821,2639,7266,5828,6561,7698,3328,6512,1351,7311,6553,
		8155,1305,722,5146,4043,12288,10810,2545,3621,8747,8785,1646,1212,
		5860,3195,7203,10963,3201,3014,955,11499,9970,11119,3135,3712,7443,
		9542,7484,8736,9995,11227,1635,9521,1177,8034,140,10436,11563,7678,
		4320,11289,9198,12208,2963,7393,2366,9238
	};

	private static final char[/*PARAM_N*/] psis_bitrev_montgomery = {
		4075,6974,7373,7965,3262,5079,522,2169,6364,1018,1041,8775,2344,
		11011,5574,1973,4536,1050,6844,3860,3818,6118,2683,1190,4789,7822,
		7540,6752,5456,4449,3789,12142,11973,382,3988,468,6843,5339,6196,3710,
		11316,1254,5435,10930,3998,10256,10367,3879,11889,1728,6137,4948,
		5862,6136,3643,6874,8724,654,10302,1702,7083,6760,56,3199,9987,605,
		11785,8076,5594,9260,6403,4782,6212,4624,9026,8689,4080,11868,6221,
		3602,975,8077,8851,9445,5681,3477,1105,142,241,12231,1003,3532,5009,
		1956,6008,11404,7377,2049,10968,12097,7591,5057,3445,4780,2920,
		7048,3127,8120,11279,6821,11502,8807,12138,2127,2839,3957,431,1579,
		6383,9784,5874,677,3336,6234,2766,1323,9115,12237,2031,6956,6413,
		2281,3969,3991,12133,9522,4737,10996,4774,5429,11871,3772,453,
		5908,2882,1805,2051,1954,11713,3963,2447,6142,8174,3030,1843,2361,
		12071,2908,3529,3434,3202,7796,2057,5369,11939,1512,6906,10474,
		11026,49,10806,5915,1489,9789,5942,10706,10431,7535,426,8974,3757,
		10314,9364,347,5868,9551,9634,6554,10596,9280,11566,174,2948,2503,
		6507,10723,11606,2459,64,3656,8455,5257,5919,7856,1747,9166,5486,
		9235,6065,835,3570,4240,11580,4046,10970,9139,1058,8210,11848,922,
		7967,1958,10211,1112,3728,4049,11130,5990,1404,325,948,11143,6190,
		295,11637,5766,8212,8273,2919,8527,6119,6992,8333,1360,2555,6167,
		1200,7105,7991,3329,9597,12121,5106,5961,10695,10327,3051,9923,
		4896,9326,81,3091,1000,7969,4611,726,1853,12149,4255,11112,2768,
		10654,1062,2294,3553,4805,2747,4846,8577,9154,1170,2319,790,11334,
		9275,9088,1326,5086,9094,6429,11077,10643,3504,3542,8668,9744,1479,
		1,8246,7143,11567,10984,4134,5736,4978,10938,5777,8961,4591,5728,
		6461,5023,9650,7468,949,9664,2975,11726,2744,9283,10092,5067,12171,
		2476,3748,11336,6522,827,9452,5374,12159,7935,3296,3949,9893,4452,
		10908,2525,3584,8112,8011,10616,4989,6958,11809,9447,12280,1022,
		11950,9821,11745,5791,5092,2089,9005,2881,3289,2013,9048,729,7901,
		1260,5755,4632,11955,2426,10593,1428,4890,5911,3932,9558,8830,3637,
		5542,145,5179,8595,3707,10530,355,3382,4231,9741,1207,9041,7012,
		1168,10146,11224,4645,11885,10911,10377,435,7952,4096,493,9908,6845,
		6039,2422,2187,9723,8643,9852,9302,6022,7278,1002,4284,5088,1607,
		7313,875,8509,9430,1045,2481,5012,7428,354,6591,9377,11847,2401,
		1067,7188,11516,390,8511,8456,7270,545,8585,9611,12047,1537,4143,
		4714,4885,1017,5084,1632,3066,27,1440,8526,9273,12046,11618,9289,
		3400,9890,3136,7098,8758,11813,7384,3985,11869,6730,10745,10111,
		2249,4048,2884,11136,2126,1630,9103,5407,2686,9042,2969,8311,9424,
		9919,8779,5332,10626,1777,4654,10863,7351,3636,9585,5291,8374,
		2166,4919,12176,9140,12129,7852,12286,4895,10805,2780,5195,2305,
		7247,9644,4053,10600,3364,3271,4057,4414,9442,7917,2174,3947,
		11951,2455,6599,10545,10975,3654,2894,7681,7126,7287,12269,4119,
		3343,2151,1522,7174,7350,11041,2442,2148,5959,6492,8330,8945,5598,
		3624,10397,1325,6565,1945,11260,10077,2674,3338,3276,11034,506,
		6505,1392,5478,8778,1178,2776,3408,10347,11124,2575,9489,12096,
		6092,10058,4167,6085,923,11251,11912,4578,10669,11914,425,10453,
		392,10104,8464,4235,8761,7376,2291,3375,7954,8896,6617,7790,1737,
		11667,3982,9342,6680,636,6825,7383,512,4670,2900,12050,7735,994,
		1687,11883,7021,146,10485,1403,5189,6094,2483,2054,3042,10945,
		3981,10821,11826,8882,8151,180,9600,7684,5219,10880,6780,204,
		11232,2600,7584,3121,3017,11053,7814,7043,4251,4739,11063,6771,
		7073,9261,2360,11925,1928,11825,8024,3678,3205,3359,11197,5209,
		8581,3238,8840,1136,9363,1826,3171,4489,7885,346,2068,1389,8257,
		3163,4840,6127,8062,8921,612,4238,10763,8067,125,11749,10125,5416,
		2110,716,9839,10584,11475,11873,3448,343,1908,4538,10423,7078,
		4727,1208,11572,3589,2982,1373,1721,10753,4103,2429,4209,5412,
		5993,9011,438,3515,7228,1218,8347,5232,8682,1327,7508,4924,448,
		1014,10029,12221,4566,5836,12229,2717,1535,3200,5588,5845,412,
		5102,7326,3744,3056,2528,7406,8314,9202,6454,6613,1417,10032,7784,
		1518,3765,4176,5063,9828,2275,6636,4267,6463,2065,7725,3495,8328,
		8755,8144,10533,5966,12077,9175,9520,5596,6302,8400,579,6781,11014,
		5734,11113,11164,4860,1131,10844,9068,8016,9694,3837,567,9348,7000,
		6627,7699,5082,682,11309,5207,4050,7087,844,7434,3769,293,9057,
		6940,9344,10883,2633,8190,3944,5530,5604,3480,2171,9282,11024,2213,
		8136,3805,767,12239,216,11520,6763,10353,7,8566,845,7235,3154,4360,
		3285,10268,2832,3572,1282,7559,3229,8360,10583,6105,3120,6643,6203,
		8536,8348,6919,3536,9199,10891,11463,5043,1658,5618,8787,5789,4719,
		751,11379,6389,10783,3065,7806,6586,2622,5386,510,7628,6921,578,
		10345,11839,8929,4684,12226,7154,9916,7302,8481,3670,11066,2334,
		1590,7878,10734,1802,1891,5103,6151,8820,3418,7846,9951,4693,417,
		9996,9652,4510,2946,5461,365,881,1927,1015,11675,11009,1371,12265,
		2485,11385,5039,6742,8449,1842,12217,8176,9577,4834,7937,9461,2643,
		11194,3045,6508,4094,3451,7911,11048,5406,4665,3020,6616,11345,
		7519,3669,5287,1790,7014,5410,11038,11249,2035,6125,10407,4565,
		7315,5078,10506,2840,2478,9270,4194,9195,4518,7469,1160,6878,2730,
		10421,10036,1734,3815,10939,5832,10595,10759,4423,8420,9617,7119,
		11010,11424,9173,189,10080,10526,3466,10588,7592,3578,11511,7785,
		9663,530,12150,8957,2532,3317,9349,10243,1481,9332,3454,3758,7899,
		4218,2593,11410,2276,982,6513,1849,8494,9021,4523,7988,8,457,648,
		150,8000,2307,2301,874,5650,170,9462,2873,9855,11498,2535,11169,
		5808,12268,9687,1901,7171,11787,3846,1573,6063,3793,466,11259,
		10608,3821,6320,4649,6263,2929
	};

	private static final char[/*PARAM_N*/] psis_inv_montgomery = {
		256,10570,1510,7238,1034,7170,6291,7921,11665,3422,4000,2327,
		2088,5565,795,10647,1521,5484,2539,7385,1055,7173,8047,11683,
		1669,1994,3796,5809,4341,9398,11876,12230,10525,12037,12253,
		3506,4012,9351,4847,2448,7372,9831,3160,2207,5582,2553,7387,6322,
		9681,1383,10731,1533,219,5298,4268,7632,6357,9686,8406,4712,9451,
		10128,4958,5975,11387,8649,11769,6948,11526,12180,1740,10782,
		6807,2728,7412,4570,4164,4106,11120,12122,8754,11784,3439,5758,
		11356,6889,9762,11928,1704,1999,10819,12079,12259,7018,11536,
		1648,1991,2040,2047,2048,10826,12080,8748,8272,8204,1172,1923,
		7297,2798,7422,6327,4415,7653,6360,11442,12168,7005,8023,9924,
		8440,8228,2931,7441,1063,3663,5790,9605,10150,1450,8985,11817,
		10466,10273,12001,3470,7518,1074,1909,7295,9820,4914,702,5367,
		7789,8135,9940,1420,3714,11064,12114,12264,1752,5517,9566,11900,
		1700,3754,5803,829,1874,7290,2797,10933,5073,7747,8129,6428,
		6185,11417,1631,233,5300,9535,10140,11982,8734,8270,2937,10953,
		8587,8249,2934,9197,4825,5956,4362,9401,1343,3703,529,10609,
		12049,6988,6265,895,3639,4031,4087,4095,585,10617,8539,4731,
		4187,9376,3095,9220,10095,10220,1460,10742,12068,1724,5513,
		11321,6884,2739,5658,6075,4379,11159,10372,8504,4726,9453,3106,
		7466,11600,10435,8513,9994,8450,9985,3182,10988,8592,2983,9204,
		4826,2445,5616,6069,867,3635,5786,11360,5134,2489,10889,12089,
		1727,7269,2794,9177,1311,5454,9557,6632,2703,9164,10087,1441,
		3717,531,3587,2268,324,5313,759,1864,5533,2546,7386,9833,8427,
		4715,11207,1601,7251,4547,11183,12131,1733,10781,10318,1474,
		10744,5046,4232,11138,10369,6748,964,7160,4534,7670,8118,8182,
		4680,11202,6867,981,8918,1274,182,26,7026,8026,11680,12202,
		10521,1503,7237,4545,5916,9623,8397,11733,10454,3249,9242,6587,
		941,1890,270,10572,6777,9746,6659,6218,6155,6146,878,1881,7291,
		11575,12187,1741,7271,8061,11685,6936,4502,9421,4857,4205,7623,
		1089,10689,1527,8996,10063,11971,10488,6765,2722,3900,9335,11867,
		6962,11528,5158,4248,4118,5855,2592,5637,6072,2623,7397,8079,
		9932,4930,5971,853,3633,519,8852,11798,3441,11025,1575,225,8810,
		11792,12218,3501,9278,3081,9218,4828,7712,8124,11694,12204,3499,
		4011,573,3593,5780,7848,9899,10192,1456,208,7052,2763,7417,11593,
		10434,12024,8740,11782,10461,3250,5731,7841,9898,1414,202,3540,
		7528,2831,2160,10842,5060,4234,4116,588,84,12,7024,2759,9172,6577,
		11473,1639,9012,3043,7457,6332,11438,1634,1989,9062,11828,8712,
		11778,12216,10523,6770,9745,10170,4964,9487,6622,946,8913,6540,
		6201,4397,9406,8366,9973,8447,8229,11709,8695,10020,3187,5722,
		2573,10901,6824,4486,4152,9371,8361,2950,2177,311,1800,9035,
		8313,11721,3430,490,70,10,1757,251,3547,7529,11609,3414,7510,
		4584,4166,9373,1339,5458,7802,11648,1664,7260,9815,10180,6721,
		9738,10169,8475,8233,9954,1422,8981,1283,5450,11312,1616,3742,
		11068,10359,4991,713,3613,9294,8350,4704,672,96,7036,9783,11931,
		3460,5761,823,10651,12055,10500,1500,5481,783,3623,11051,8601,
		8251,8201,11705,10450,5004,4226,7626,2845,2162,3820,7568,9859,
		3164,452,10598,1514,5483,6050,6131,4387,7649,8115,6426,918,8909,
		8295,1185,5436,11310,8638,1234,5443,11311,5127,2488,2111,10835,
		5059,7745,2862,3920,560,80,1767,2008,3798,11076,6849,2734,10924,
		12094,8750,1250,10712,6797,971,7161,1023,8924,4786,7706,4612,4170,
		7618,6355,4419,5898,11376,10403,10264,6733,4473,639,5358,2521,
		9138,3061,5704,4326,618,5355,765,5376,768,7132,4530,9425,3102,
		9221,6584,11474,10417,10266,12000,6981,6264,4406,2385,7363,4563,
		4163,7617,9866,3165,9230,11852,10471,5007,5982,11388,5138,734,
		3616,11050,12112,6997,11533,12181,10518,12036,3475,2252,7344,
		9827,4915,9480,6621,4457,7659,9872,6677,4465,4149,7615,4599,657,
		3605,515,10607,6782,4480,640,1847,3775,5806,2585,5636,9583,1369,
		10729,8555,10000,11962,5220,7768,8132,8184,9947,1421,203,29,8782,
		11788,1684,10774,10317,4985,9490,8378,4708,11206,5112,5997,7879,
		11659,12199,8765,10030,4944,5973,6120,6141,6144,7900,11662,1666,
		238,34,3516,5769,9602,8394,9977,6692,956,10670,6791,9748,11926,
		8726,11780,5194,742,106,8793,10034,3189,10989,5081,4237,5872,4350,
		2377,10873,6820,6241,11425,10410,10265,3222,5727,9596,4882,2453,
		2106,3812,11078,12116,5242,4260,11142,8614,11764,12214,5256,4262,
		4120,11122,5100,11262,5120,2487,5622,9581,8391,8221,2930,10952,
		12098,6995,6266,9673,4893,699,3611,4027,5842,11368,1624,232,8811,
		8281,1183,169,8802,3013,2186,5579,797,3625,4029,11109,1587,7249,
		11569,8675,6506,2685,10917,12093,12261,12285,1755,7273,1039,1904,
		272,3550,9285,3082,5707,6082,4380,7648,11626,5172,4250,9385,8363,
		8217,4685,5936,848,8899,6538,934,1889,3781,9318,10109,10222,6727,
		961,5404,772,5377,9546,8386,1198,8949,3034,2189,7335,4559,5918,2601,
		10905,5069,9502,3113,7467,8089,11689,5181,9518,8382,2953,3933,4073,
		4093,7607,8109,2914,5683,4323,11151,1593,10761,6804,972,3650,2277,
		5592,4310,7638,9869,4921,703,1856,9043,4803,9464,1352,8971,11815,
		5199,7765,6376,4422,7654,2849,407,8836,6529,7955,2892,9191,1313,
		10721,12065,12257,1751,9028,8312,2943,2176,3822,546,78,8789,11789,
		10462,12028,6985,4509,9422,1346,5459,4291,613,10621,6784,9747,3148,
		7472,2823,5670,810,7138,8042,4660,7688,6365,6176,6149,2634,5643,
		9584,10147,11983,5223,9524,11894,10477,8519,1217,3685,2282,326,
		10580,3267,7489,4581,2410,5611,11335,6886,8006,8166,11700,3427,
		11023,8597,10006,3185,455,65,5276,7776,4622,5927,7869,9902,11948,
		5218,2501,5624,2559,10899,1557,1978,10816,10323,8497,4725,675,1852,
		10798,12076,10503,3256,9243,3076,2195,10847,12083,10504,12034,10497
	};
}
