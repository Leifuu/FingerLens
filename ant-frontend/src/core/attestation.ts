import * as asn1js from 'asn1js';
import { Certificate, CertificateChainValidationEngine, CryptoEngine, setEngine } from 'pkijs';
import snapshot from '../../../shared/trust-roots.json';
import type { Fingerprint, Json } from './types';

export interface AttestationResult { key: string; status: string; checks: Record<string, Json>; certificates?: Json[]; error?: string }
export interface TrustSnapshot { source: string; fetchedAt: string | null; sha256: string | null; status: string; certificates: string[] }
export function decode64(text: string): ArrayBuffer { if (text.length > 2 * 1024 * 1024 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text) || text.length % 4) throw new Error('证书或签名 Base64 非法'); const binary = atob(text); return Uint8Array.from(binary, c => c.charCodeAt(0)).buffer; }
type Node = asn1js.BaseBlock;
function children(node: Node): Node[] { const value = (node.valueBlock as unknown as { value?: Node[] }).value; if (!Array.isArray(value)) throw new Error('ASN.1 结构不匹配'); return value; }
function integer(node: Node): number { const value = (node.valueBlock as unknown as { valueDec?: number }).valueDec; if (typeof value !== 'number' || !Number.isSafeInteger(value)) throw new Error('ASN.1 整数非法'); return value; }
function octets(node: Node): Uint8Array { const bytes = (node.valueBlock as unknown as { valueHexView?: Uint8Array }).valueHexView; if (!bytes) throw new Error('ASN.1 字节串非法'); return bytes; }
function hex(bytes: Uint8Array): string { return Array.from(bytes, x => x.toString(16).padStart(2, '0')).join(''); }
function boundedAsn1(bytes: ArrayBuffer): Node { if (bytes.byteLength > 1024 * 1024) throw new Error('ASN.1 超过1MiB'); const parsed = asn1js.fromBER(bytes); if (parsed.offset !== bytes.byteLength || parsed.offset === -1) throw new Error('ASN.1 编码非法或存在尾随字节'); let count = 0; const walk = (node: Node, depth: number) => { if (++count > 10000 || depth > 32) throw new Error('ASN.1 复杂度超限'); const value = node.idBlock.isConstructed ? (node.valueBlock as unknown as { value?: Node[] }).value : undefined; if (Array.isArray(value)) value.forEach(x => walk(x, depth + 1)); }; walk(parsed.result, 0); return parsed.result; }
export function parseAndroidExtension(cert: Certificate, challenge: ArrayBuffer): Record<string, Json> {
  const extension = cert.extensions?.find(x => x.extnID === '1.3.6.1.4.1.11129.2.1.17'); if (!extension) return { present: false };
  const fields = children(boundedAsn1(extension.extnValue.valueBlock.valueHexView.slice().buffer)); if (fields.length !== 8) throw new Error('KeyDescription 字段数量异常');
  const result: Record<string, Json> = { present: true, attestationVersion: integer(fields[0]), attestationSecurityLevel: integer(fields[1]), keymasterVersion: integer(fields[2]), keymasterSecurityLevel: integer(fields[3]), challengeMatches: hex(octets(fields[4])) === hex(new Uint8Array(challenge)) };
  for (const [label, list] of [['softwareEnforced', fields[6]], ['hardwareEnforced', fields[7]]] as const) {
    const parsed: Record<string, Json> = Object.create(null); const seen = new Set<number>();
    for (const field of children(list)) {
      const tag = field.idBlock.tagNumber; if (seen.has(tag)) throw new Error(`认证扩展重复 tag ${tag}`); seen.add(tag);
      const inner = children(field)[0]; if (!inner) throw new Error('空认证扩展');
      if ([705, 706, 718, 719].includes(tag)) parsed[String(tag)] = integer(inner);
      if (tag === 704) { const root = children(inner); if (root.length < 3 || root.length > 4) throw new Error('RootOfTrust 结构异常'); parsed.rootOfTrust = { verifiedBootKeyHex: hex(octets(root[0])), deviceLocked: (root[1].valueBlock as unknown as { value: boolean }).value, verifiedBootState: integer(root[2]), verifiedBootHashHex: root[3] ? hex(octets(root[3])) : null }; }
      if (tag === 709) {
        const identity = children(boundedAsn1(octets(inner).slice().buffer)); if (identity.length !== 2) throw new Error('认证应用身份结构异常');
        const packages = children(identity[0]).map(x => { const p = children(x); return { packageName: new TextDecoder('utf-8', { fatal: true }).decode(octets(p[0])), version: String((p[1].valueBlock as unknown as { valueDec: number }).valueDec) }; });
        parsed.applicationId = { packages, signatureDigests: children(identity[1]).map(x => hex(octets(x))) };
      }
    }
    result[label] = parsed;
  }
  return result;
}
function pemBytes(pem: string): ArrayBuffer { return decode64(pem.replace(/-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\s/g, '')); }
export async function verifyAttestation(fp: Fingerprint, trust: TrustSnapshot = snapshot): Promise<AttestationResult[]> {
  setEngine('webcrypto', crypto, new CryptoEngine({ name: 'webcrypto', crypto, subtle: crypto.subtle }));
  const results: AttestationResult[] = [];
  for (const item of fp.data.items.filter(x => x.category === 'attestation' && ['attestation/tee', 'attestation/plain', 'attestation/strongbox'].includes(x.key))) {
    const checks: Record<string, Json> = { revocation: '离线未检查实时撤销', trustSnapshot: { source: trust.source, fetchedAt: trust.fetchedAt, sha256: trust.sha256, status: trust.status, rootCount: trust.certificates.length }, verificationTime: new Date().toISOString(), freshness: '挑战由本地生成，不证明远程会话新鲜性', verdict: '本地密码学校验，不等于 Play Integrity 或第三方风控结论' };
    if (item.status !== 'ok') { results.push({ key: item.key, status: item.status, checks, error: '未取得可验证的认证材料' }); continue; }
    try {
      const v = item.value as Record<string, Json>; const chain = v.certificateChainBase64;
      if (!Array.isArray(chain) || chain.length < 1 || chain.length > 16 || chain.some(x => typeof x !== 'string')) throw new Error('证书链长度或类型非法');
      const certs = (chain as string[]).map(x => new Certificate({ schema: boundedAsn1(decode64(x)) }));
      const signatures: boolean[] = []; for (let i = 0; i < certs.length - 1; i++) signatures.push(await certs[i].verify(certs[i + 1]));
      checks.chainSignatures = signatures;
      const challenge = decode64(String(v.challengeBase64)), signature = decode64(String(v.signatureBase64));
      const spki = certs[0].subjectPublicKeyInfo.toSchema().toBER(false);
      checks.leafPublicKeyMatches = hex(new Uint8Array(spki)) === hex(new Uint8Array(decode64(String(v.publicKeyBase64))));
      checks.challengeSignature = await new CryptoEngine({ name: 'webcrypto', crypto, subtle: crypto.subtle }).verifyWithPublicKey(challenge, new asn1js.OctetString({ valueHex: signature }), certs[0].subjectPublicKeyInfo, new (await import('pkijs')).AlgorithmIdentifier({ algorithmId: '1.2.840.10045.4.3.2' }));
      checks.androidExtension = parseAndroidExtension(certs[0], challenge);
      const ext = checks.androidExtension as Record<string, Json>;
      const identities: Record<string, Json>[] = []; for (const label of ['softwareEnforced', 'hardwareEnforced']) { const side = ext[label] as Record<string, Json> | undefined; if (side?.applicationId) identities.push(side.applicationId as Record<string, Json>); }
      checks.applicationIdentity = identities.length ? identities.map(identity => ({ packageMatches: Array.isArray(identity.packages) && identity.packages.some(p => !!p && typeof p === 'object' && !Array.isArray(p) && p.packageName === fp.data.app.packageName && p.version === String(fp.data.app.versionCode)), signatureMatches: Array.isArray(fp.data.app.signatures) && Array.isArray(identity.signatureDigests) && fp.data.app.signatures.every(s => !!s && typeof s === 'object' && !Array.isArray(s) && identity.signatureDigests instanceof Array && identity.signatureDigests.includes(s.sha256)) })) : '扩展未提供应用身份';
      const trusted = trust.certificates.map(pem => new Certificate({ schema: boundedAsn1(pemBytes(pem)) }));
      checks.trustedChain = trusted.length ? (await new CertificateChainValidationEngine({ trustedCerts: trusted, certs, checkDate: new Date() }).verify()).result : '未知：未配置可信公开根快照';
      checks.validAtBrowserClock = certs.map(c => Date.now() >= c.notBefore.value.getTime() && Date.now() <= c.notAfter.value.getTime());
      results.push({ key: item.key, status: '已完成本地检查', checks, certificates: certs.map(c => ({ subject: c.subject.toJSON() as unknown as Json, issuer: c.issuer.toJSON() as unknown as Json, serialHex: hex(c.serialNumber.valueBlock.valueHexView), notBefore: c.notBefore.value.toISOString(), notAfter: c.notAfter.value.toISOString() })) });
    } catch (error) { results.push({ key: item.key, status: '校验失败', checks, error: error instanceof Error ? error.message : String(error) }); }
  }
  return results;
}
