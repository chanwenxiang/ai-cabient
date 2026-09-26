/**
 * 补货任务证据本地下载映射（debt-tracker M4b）。
 * 纯编排：download 由调用方注入（merchantApi）；禁止本模块依赖 uni。
 */

export type ReplenishmentEvidenceRef = {
  fileId?: number;
  url?: string;
};

export type MappedReplenishmentEvidence = {
  localPath: string;
  fileId?: number;
};

/** 下载失败或无 fileId 时回退远端 url（可空串）。 */
export function evidenceLocalFallback(
  file: ReplenishmentEvidenceRef,
  downloadedPath?: string | null
): MappedReplenishmentEvidence {
  if (downloadedPath) {
    return { localPath: downloadedPath, fileId: file.fileId };
  }
  return { localPath: file.url || '', fileId: file.fileId };
}

/**
 * 将证据列表映射为可预览本地项。
 * 有 fileId 则走 download；失败回退 url。无 fileId 直接用 url。
 */
export async function mapReplenishmentEvidenceItems(
  evidence: ReplenishmentEvidenceRef[] | null | undefined,
  download: (fileId: number) => Promise<string>
): Promise<MappedReplenishmentEvidence[]> {
  return Promise.all(
    (evidence || []).map(async (f) => {
      const fileId = f.fileId;
      if (fileId == null || !Number.isFinite(fileId) || fileId <= 0) {
        return evidenceLocalFallback(f);
      }
      try {
        const localPath = await download(fileId);
        return evidenceLocalFallback(f, localPath);
      } catch {
        return evidenceLocalFallback(f);
      }
    })
  );
}

/** 详情加载后把张数写回列表徽章 map。 */
export function mergeEvidenceCountMap(
  prev: Record<number, number>,
  taskId: number,
  count: number
): Record<number, number> {
  return { ...prev, [taskId]: count };
}
