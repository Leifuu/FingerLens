import { describe, it, expect } from 'vitest';
import * as asn1js from 'asn1js';
import { Certificate, AttributeTypeAndValue, Extension, BasicConstraints, CryptoEngine, setEngine } from 'pkijs';
import { verifyAttestation, type TrustSnapshot } from './attestation';
import { fixture, fixtureItem } from './fixtures';
const b64 = (bytes: ArrayBuffer) => Buffer.from(bytes).toString('base64');
const unknownTrust: TrustSnapshot = { source: 'synthetic-test-only', fetchedAt: null, sha256: null, status: 'unavailable', certificates: [] };
async function materials() {
  setEngine('webcrypto', crypto, new CryptoEngine({ name: 'webcrypto', crypto, subtle: crypto.subtle }));
  const rootKey = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const leafKey = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const make = async (name: string, key: CryptoKeyPair, serial: number) => { const cert = new Certificate(); cert.version = 2; cert.serialNumber = new asn1js.Integer({ value: serial }); cert.subject.typesAndValues.push(new AttributeTypeAndValue({ type: '2.5.4.3', value: new asn1js.Utf8String({ value: name }) })); cert.notBefore.value = new Date(Date.now() - 86400000); cert.notAfter.value = new Date(Date.now() + 86400000); await cert.subjectPublicKeyInfo.importKey(key.publicKey); return cert; };
  const root = await make('SYNTHETIC TEST ROOT - NOT A DEVICE', rootKey, 1); root.issuer = root.subject; root.extensions = [new Extension({ extnID: '2.5.29.19', critical: true, extnValue: new BasicConstraints({ cA: true }).toSchema().toBER(false) })]; await root.sign(rootKey.privateKey, 'SHA-256');
  const challenge = crypto.getRandomValues(new Uint8Array(32));
  const extension = new asn1js.Sequence({ value: [new asn1js.Integer({ value: 4 }), new asn1js.Enumerated({ value: 1 }), new asn1js.Integer({ value: 4 }), new asn1js.Enumerated({ value: 1 }), new asn1js.OctetString({ valueHex: challenge.buffer }), new asn1js.OctetString(), new asn1js.Sequence(), new asn1js.Sequence({ value: [new asn1js.Constructed({ idBlock: { tagClass: 3, tagNumber: 705 }, value: [new asn1js.Integer({ value: 100000 })] })] })] });
  const leaf = await make('SYNTHETIC TEST LEAF', leafKey, 2); leaf.issuer = root.subject; leaf.extensions = [new Extension({ extnID: '1.3.6.1.4.1.11129.2.1.17', extnValue: extension.toBER(false) })]; await leaf.sign(rootKey.privateKey, 'SHA-256');
  // WebCrypto 给 P1363，Android Signature 给 DER；测试显式转成 DER。
  const signature = new Uint8Array(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, leafKey.privateKey, challenge));
  const int = (bytes: Uint8Array) => { let n = 0; while (n < bytes.length - 1 && bytes[n] === 0) n++; const data = bytes.slice(n); return new asn1js.Integer({ valueHex: (data[0] & 128 ? Uint8Array.from([0, ...data]) : data).buffer }); };
  const der = new asn1js.Sequence({ value: [int(signature.slice(0, 32)), int(signature.slice(32))] }).toBER(false);
  return { value: { requestedAttestation: true, challengeBase64: b64(challenge.buffer), signatureBase64: b64(der), publicKeyBase64: b64(await crypto.subtle.exportKey('spki', leafKey.publicKey)), certificateChainBase64: [b64(leaf.toSchema(true).toBER(false)), b64(root.toSchema(true).toBER(false))] }, leaf, root };
}
describe('合成认证夹具，不是设备实测', () => {
  it('仅在显式信任合成根时，该测试链可通过根链校验', async () => { const m = await materials(); const pem = `-----BEGIN CERTIFICATE-----\n${b64(m.root.toSchema(true).toBER(false))}\n-----END CERTIFICATE-----`; const trust = { ...unknownTrust, certificates: [pem] }; const [result] = await verifyAttestation(fixture([fixtureItem('attestation/tee', m.value, { category: 'attestation' })]), trust); expect(result.error).toBeUndefined(); expect(result.checks.trustedChain).toBe(true); });
  it('篡改叶证书主体会使证书签名校验失败', async () => { const m = await materials(); m.leaf.subject.typesAndValues[0].value = new asn1js.Utf8String({ value: 'SYNTHETIC TAMPERED' }); m.value.certificateChainBase64[0] = b64(m.leaf.toSchema(true).toBER(false)); const [result] = await verifyAttestation(fixture([fixtureItem('attestation/tee', m.value, { category: 'attestation' })]), unknownTrust); expect(result.checks.chainSignatures).toEqual([false]); });
  it('验证签名链、挑战签名与扩展，未知根保持未知', async () => { const m = await materials(); const [result] = await verifyAttestation(fixture([fixtureItem('attestation/tee', m.value, { category: 'attestation' })]), unknownTrust); expect(result.error).toBeUndefined(); expect(result.checks.chainSignatures).toEqual([true]); expect(result.checks.challengeSignature).toBe(true); expect(result.checks.leafPublicKeyMatches).toBe(true); expect(result.checks.androidExtension).toMatchObject({ challengeMatches: true }); expect(result.checks.trustedChain).toMatch(/未知/); });
  it('挑战或签名篡改不能通过密码学校验', async () => { const m = await materials(); m.value.challengeBase64 = b64(new Uint8Array(32).buffer); const [result] = await verifyAttestation(fixture([fixtureItem('attestation/tee', m.value, { category: 'attestation' })])); expect(result.checks.challengeSignature).toBe(false); expect(result.checks.androidExtension).toMatchObject({ challengeMatches: false }); });
  it('拒绝异常证书、过长链与错误扩展', async () => { const bad = fixture([fixtureItem('attestation/tee', { certificateChainBase64: ['!!!!'] }, { category: 'attestation' })]); expect((await verifyAttestation(bad))[0].status).toBe('校验失败'); const m = await materials(); m.leaf.extensions![0].extnValue = new asn1js.OctetString({ valueHex: Uint8Array.of(1, 2, 3).buffer }); m.value.certificateChainBase64[0] = b64(m.leaf.toSchema(true).toBER(false)); expect((await verifyAttestation(fixture([fixtureItem('attestation/tee', m.value, { category: 'attestation' })])))[0].status).toBe('校验失败'); });
});
