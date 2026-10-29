<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商品学习库</span>
            <span class="hint">
              租户级学习资产总览：将邑云端学会的商品与模型，一次学习所有柜机共用；
              但「能不能卖」仍逐柜——类别要在「识别映射」里挂到我方 SKU 并配价与库存
            </span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :loading="loading" @click="load()">刷新</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="warning"
            :disabled="!cabinets.length"
            @click="openConsole()"
          >
            开始采集
          </el-button>
          <el-button @click="openStudying()">学习中商品</el-button>
          <el-button @click="openGatherCheck()">采集审核</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="primary"
            :disabled="!cabinets.length"
            @click="openTraining()"
          >
            触发学习
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="链路：挂接 → 采集 → 审核 → 学习 → 下发 → 上架"
    >
      <template #default>
        「开始采集」柜前拍摄回传（期间停营业）→ 厂商人工审核 → 「触发学习」云端训练（无需柜机在线）→
        到<el-link type="primary" @click="goMappings()">识别映射</el-link>预生成映射、复核、下发 →
        「跨柜上架」开卖。
      </template>
    </el-alert>

    <VisionFlowSteps :active="1" />

    <el-alert
      v-if="library?.vendorError"
      class="vendor-alert"
      type="warning"
      :closable="false"
      show-icon
      title="将邑读面降级：下面的模型与可学习商品可能为空"
      :description="library.vendorError"
    />

    <div class="stat-line">
      <span
        >模型 <b>{{ library?.models.length ?? 0 }}</b></span
      >
      <span
        >可学习商品 <b>{{ library?.learnableProducts.length ?? 0 }}</b></span
      >
      <span
        >已接入柜机 <b>{{ cabinets.length }}</b></span
      >
      <span
        >可发起学习的 SKU <b>{{ learnableSkuCount }}</b></span
      >
    </div>

    <el-tabs v-model="activeTab" class="library-tabs">
      <el-tab-pane label="可学习商品" name="products">
        <div class="filter-bar filter-bar--compact">
          <el-input
            v-model="keyword"
            clearable
            placeholder="按厂商名 / 识别名 / 条码 / 我方 SKU 过滤"
            style="width: 320px"
          />
          <span class="form-hint">
            在列＝采集成档且厂商审核通过，<b>不等于已学成</b>；「未上架」＝该识别名没有 ACTIVE
            识别映射：<b>柜内在售 ≠ 识别上架</b>，识别结算以映射为准
          </span>
        </div>
        <el-table v-loading="loading" :data="pagedProducts" size="small" border>
          <el-table-column prop="name" label="厂商商品名" min-width="160">
            <template #default="{ row }">{{ row.name || '—' }}</template>
          </el-table-column>
          <el-table-column prop="textName" label="识别名称（classes）" min-width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.textName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="barCode" label="条码" min-width="150">
            <template #default="{ row }">
              <span class="mono">{{ row.barCode || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="我方商品" min-width="170">
            <template #default="{ row }">
              <template v-if="row.skuId">
                <div>{{ row.skuName || row.skuId }}</div>
                <div class="sub mono">{{ row.skuId }}</div>
              </template>
              <el-tag v-else size="small" type="warning">未挂我方商品</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="已上架柜机（ACTIVE 识别映射）" min-width="240">
            <template #default="{ row }">
              <template v-if="row.coverage.length">
                <el-tag
                  v-for="c in row.coverage"
                  :key="`${c.deviceId}-${c.classId}`"
                  class="coverage-tag"
                  size="small"
                  :type="c.mappingVerified ? 'success' : 'info'"
                >
                  {{ c.deviceName || c.deviceId }} · 编号 {{ c.classId }}
                  {{ c.mappingVerified ? '' : '（未复核）' }}
                </el-tag>
              </template>
              <el-tooltip
                content="柜内在售 ≠ 识别上架：该识别名在这台柜的模型映射里没有 ACTIVE 行，摄像头认出它也不会按此商品结算；到识别映射页激活后才算识别上架"
                placement="top"
              >
                <span class="muted">未上架</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-tooltip
                v-if="!row.skuId"
                content="未挂我方商品：先到商品挂接页绑定 SKU 才能上架"
                placement="top"
              >
                <span class="muted">不可上架</span>
              </el-tooltip>
              <el-button
                v-else
                v-hasPermi="['ops:device:edit']"
                link
                type="primary"
                @click="openShelf(row)"
              >
                跨柜上架
              </el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="library && !library.learnableProducts.length" class="empty-note">
          将邑侧的可学习商品名单为空。先在柜机前采集，厂商审核通过后才进名单——进名单只说明「可学」，
          学没学成看「模型」与「学习中」两个选项卡。
        </div>
        <el-pagination
          v-model:current-page="productPage"
          v-model:page-size="productSize"
          :total="filteredProducts.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="table-pagination"
        />
      </el-tab-pane>

      <el-tab-pane label="柜机" name="cabinets">
        <div class="filter-bar filter-bar--compact">
          <el-input
            v-model="cabinetKeyword"
            clearable
            placeholder="按柜机名 / 编号 / identifier 过滤"
            style="width: 320px"
          />
        </div>
        <el-table v-loading="loading" :data="pagedCabinets" size="small" border>
          <el-table-column label="柜机" min-width="230">
            <template #default="{ row }">
              <div>{{ row.deviceName || row.deviceId }}</div>
              <div class="sub mono">
                {{ row.deviceId }} · {{ row.identifier || '无 identifier' }}
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="classesVersion" label="模型 classes 版本" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.classesVersion || '未登记' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="映射复核" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.mappingVerified ? 'success' : 'danger'">
                {{ row.mappingVerified ? '已复核' : '未复核' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="activeMappingCount" label="在架类别" width="100" />
          <el-table-column label="采集模式" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.gatherLocked" size="small" type="warning">采集中</el-tag>
              <span v-else class="muted">正常营业</span>
            </template>
          </el-table-column>
          <el-table-column label="最近通信" min-width="160">
            <template #default="{ row }">
              {{ row.lastWsOnlineAt ? formatInstant(row.lastWsOnlineAt) : '从未上线' }}
            </template>
          </el-table-column>
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="goMappings(row.deviceId)">识别映射</el-button>
              <el-button link type="primary" @click="goDevice(row.deviceId)">设备详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="cabinetPage"
          v-model:page-size="cabinetSize"
          :total="filteredCabinets.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="table-pagination"
        />
      </el-tab-pane>

      <el-tab-pane label="模型" name="models">
        <div class="filter-bar filter-bar--compact">
          <el-input
            v-model="modelKeyword"
            clearable
            placeholder="按模型名 / 厂商初命名过滤"
            style="width: 320px"
          />
          <span class="form-hint">
            模型名以厂商回包为准——我方提交的名字被厂商改写时，只有「厂商初命名」列留着原名
          </span>
        </div>
        <el-table :data="pagedModels" size="small" border>
          <el-table-column prop="modelName" label="模型名" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.modelName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="customerModelName" label="厂商初命名" min-width="170">
            <template #default="{ row }">
              <span class="mono">{{ row.customerModelName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="类别数" width="90" />
          <el-table-column prop="industrialControlModel" label="工控机型" width="100">
            <template #default="{ row }">
              <span class="mono">{{ row.industrialControlModel || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="modelTextUrl" label="classes 地址" min-width="280">
            <template #default="{ row }">
              <span class="mono ellipsis" :title="row.modelTextUrl || ''">
                {{ row.modelTextUrl || '—' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-model:current-page="modelPage"
          v-model:page-size="modelSize"
          :total="filteredModels.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="table-pagination"
        />
      </el-tab-pane>

      <el-tab-pane label="最近学习" name="trainings">
        <div class="filter-bar filter-bar--compact">
          <el-input
            v-model="trainingKeyword"
            clearable
            placeholder="按商品 / 模型 / 柜机过滤"
            style="width: 320px"
          />
        </div>
        <el-table :data="pagedTrainings" size="small" border>
          <el-table-column label="发起时间" min-width="160">
            <template #default="{ row }">{{ formatInstant(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="柜机" min-width="150">
            <template #default="{ row }">{{ row.deviceName || row.deviceId || '—' }}</template>
          </el-table-column>
          <el-table-column label="商品" min-width="170">
            <template #default="{ row }">{{ row.skuName || row.skuId || '—' }}</template>
          </el-table-column>
          <el-table-column prop="modelName" label="模型" min-width="140">
            <template #default="{ row }">
              <span class="mono">{{ row.modelName || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="trainingStatusType(row.status)">
                {{ trainingStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="完成时间" min-width="160">
            <template #default="{ row }">
              {{ row.finishedAt ? formatInstant(row.finishedAt) : '—' }}
            </template>
          </el-table-column>
        </el-table>
        <div class="empty-note">
          在厂商商户 App
          里发起的学习不会出现在这里（回调走厂商自己的地址）；本页发起的学习，回调成功后会自动退出该柜的采集模式。
        </div>
        <el-pagination
          v-model:current-page="trainingPage"
          v-model:page-size="trainingSize"
          :total="filteredTrainings.length"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          class="table-pagination"
        />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="trainingVisible" title="批量触发学习" width="600px">
      <el-alert
        class="dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="一次提交只发一次厂商学习调用，覆盖勾选的全部商品"
      >
        <template #default>
          将邑学习是<b>集合粒度</b>——一次提交就把已采集的全部商品重训一遍，勾几个都算同一次，
          柜机只决定「进度挂在谁身上」。本页只拦「未挂将邑商品」的 SKU（票据要按厂商商品 id
          记账）；按将邑流程，商品要先在柜机前采集并通过厂商审核才会进可学习名单。
          若柜机已在我方采集模式中，学习回调成功后会<b>自动退出采集模式</b>并恢复营业。
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="柜机" required>
          <el-select
            v-model="trainingForm.deviceId"
            filterable
            placeholder="选择发起学习的柜机"
            style="width: 100%"
          >
            <el-option
              v-for="c in cabinets"
              :key="c.deviceId"
              :label="`${c.deviceName || c.deviceId}（${c.identifier || '无编号'}）`"
              :value="c.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" required>
          <el-select
            v-model="trainingForm.skuIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="勾选本次要学的我方 SKU（已挂将邑商品）"
            style="width: 100%"
          >
            <el-option
              v-for="s in learnableSkus"
              :key="s.skuId"
              :label="`${s.skuName || s.skuId}（${
                s.inVendorList ? '在厂商可学习名单' : '不在厂商可学习名单'
              }${s.mappedDeviceIds.length ? ' · 已进识别链路' : ''}）`"
              :value="s.skuId"
            />
          </el-select>
          <div class="form-hint">
            只列出已挂将邑商品的 SKU。『在厂商可学习名单』＝该商品当前出现在将邑的可学习名单里，
            是本次训练覆盖的对象；名单只证「可学」，<b>不证已学成</b>。『已进识别链路』＝该识别名
            已在某柜有 ACTIVE 挂牌，这才能被摄像头认出。有在途学习的商品提交会被拒（等回执后再试）。
          </div>
        </el-form-item>
        <el-form-item label="模型名" required>
          <el-select
            v-if="library?.models.length"
            v-model="trainingForm.modelName"
            filterable
            placeholder="选择要重训的模型名"
            style="width: 100%"
          >
            <el-option
              v-for="m in library?.models ?? []"
              :key="m.id"
              :label="`${m.modelName}（${m.quantity} 类）`"
              :value="m.modelName || ''"
            />
          </el-select>
          <el-input
            v-else
            v-model="trainingForm.modelName"
            placeholder="将邑当前没有学成模型，填写要新建的模型名"
          />
          <div v-if="!library?.models.length" class="form-hint">
            将邑模型列表为空（尚无学成模型）：此处手填的模型名就是首次训练要生成的模型，
            名字要和后续下发的 classes/模型对齐。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trainingVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="trainingSubmitting"
          :disabled="!trainingForm.skuIds.length"
          @click="submitTraining"
        >
          提交学习（{{ trainingForm.skuIds.length }}）
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shelfVisible" title="跨柜上架" width="720px">
      <el-alert
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="只挂柜机当前模型里同名的识别映射"
      >
        <template #default>
          按将邑侧识别名「<b class="mono">{{ shelfProduct?.textName || '—' }}</b
          >」把商品挂到所选柜机
          <b>当前 classes 里同名的那一行</b
          >，并置为在售——不改识别编号、不新建类目（编号由各柜模型决定）。
          未按当前模型复核、当前模型没有这个识别名、或该识别名已挂其它 SKU
          的柜机会被<b>跳过</b>，逐柜给出原因。
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="商品">
          <div v-if="shelfProduct">
            {{ shelfProduct.skuName || shelfProduct.skuId }}
            <span class="sub mono"> · {{ shelfProduct.skuId }}</span>
          </div>
        </el-form-item>
        <el-form-item label="柜机" required>
          <el-select
            v-model="shelfForm.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择要上架的柜机"
            style="width: 100%"
          >
            <el-option
              v-for="c in cabinets"
              :key="c.deviceId"
              :label="`${c.deviceName || c.deviceId}（${c.mappingVerified ? '已复核' : '未复核'}）`"
              :value="c.deviceId"
            />
          </el-select>
          <div class="form-hint">
            一次最多 20 台；未复核的柜机提交后会被跳过（先去识别映射页复核）。
          </div>
        </el-form-item>
        <el-form-item label="柜级价">
          <el-input-number
            v-model="shelfForm.priceYuan"
            :min="0.01"
            :max="99999"
            :precision="2"
            :step="0.5"
            :disabled="!canEditPricing"
            placeholder="留空不改价"
            style="width: 220px"
          />
          <div class="form-hint">
            {{
              canEditPricing
                ? '留空＝不动价格（沿用现有柜级价或基准价）；填写则写本柜覆盖价（受 SKU 限价约束）。'
                : '没有改价权限（ops:pricing:edit）：本次只挂接，不调价。'
            }}
          </div>
        </el-form-item>
        <el-form-item label="铺货件数">
          <el-input-number
            v-model="shelfForm.quantity"
            :min="1"
            :max="999"
            :disabled="!canEditReplenishment"
            placeholder="留空不铺货"
            style="width: 220px"
          />
          <div class="form-hint">
            {{
              canEditReplenishment
                ? '只补「账面为 0 且未启用批次账本」的柜；已有库存或批次账本的柜不改动（原因见逐柜结果）。'
                : '没有补货权限（ops:replenishment:edit）：本次只挂接，不铺货。'
            }}
          </div>
        </el-form-item>
      </el-form>
      <template v-if="shelfResult">
        <el-divider content-position="left">逐柜结果</el-divider>
        <el-table :data="shelfResult.devices" size="small" border>
          <el-table-column label="柜机" min-width="150">
            <template #default="{ row }">{{ row.deviceName || row.deviceId }}</template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="shelfStatusType(row.status)">
                {{ shelfStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="识别编号" width="90">
            <template #default="{ row }">{{ row.classId ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="260">
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="closeShelf">{{ shelfResult ? '关闭' : '取消' }}</el-button>
        <el-button
          type="primary"
          :loading="shelfSubmitting"
          :disabled="!shelfForm.deviceIds.length"
          @click="submitShelf"
        >
          上架到 {{ shelfForm.deviceIds.length }} 台柜机
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="studyingVisible" title="学习中商品" width="720px">
      <el-alert
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="出现在本列表＝正在学习或排队训练；「审核」是条目准入状态（通过才开始训练），学成以「模型」选项卡出现为准"
      >
        <template #default>
          审核态：pass 审核通过 / reject 驳回 / wait 待审核。厂商商户 App 里发起的学习也在这里，
          与本页「最近学习」（只记我方发起的）互不相干。
        </template>
      </el-alert>
      <div class="dialog-toolbar">
        <el-button :loading="studyingLoading" @click="loadStudying()">刷新</el-button>
      </div>
      <el-table v-loading="studyingLoading" :data="studyingRows" size="small" border>
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="170">
          <template #default="{ row }">
            <el-tag size="small">学习中</el-tag>
            <div class="sub" style="margin-top: 4px">审核：{{ auditStatusText(row.status) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="驳回原因" min-width="180">
          <template #default="{ row }">{{ row.rejectCause || '—' }}</template>
        </el-table-column>
        <el-table-column label="图片" width="90">
          <template #default="{ row }">
            <el-image
              v-if="row.mainImg"
              :src="row.mainImg"
              :preview-src-list="[row.mainImg]"
              preview-teleported
              fit="cover"
              style="width: 56px; height: 56px"
            />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!studyingLoading && !studyingRows.length" class="empty-note">
        将邑侧当前没有学习中的商品：先采集回传并通过厂商审核（采集审核按钮），再触发学习。
      </div>
      <template #footer>
        <el-button @click="studyingVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="gatherCheckVisible" title="采集审核列表" width="780px">
      <el-alert
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="采集回传后先过厂商审核：wait=待审核，通过（pass）才进可学习名单，驳回（reject）要重新采集"
      />
      <div class="dialog-toolbar">
        <el-input
          v-model="gatherCheckProductId"
          clearable
          placeholder="按厂商商品 id 直查（如 41175，未挂我方 SKU 也能查）；留空＝按挂接/全部"
          style="width: 380px"
          @keyup.enter="queryGatherCheck()"
        />
        <el-button type="primary" :loading="gatherCheckLoading" @click="queryGatherCheck()">
          查询
        </el-button>
      </div>
      <el-table v-loading="gatherCheckLoading" :data="gatherCheckRows" size="small" border>
        <el-table-column label="商品 id" width="90">
          <template #default="{ row }">
            <span class="mono">{{ row.productId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="商品" min-width="200">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="auditStatusType(row.status)">
              {{ auditStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="驳回原因" min-width="170">
          <template #default="{ row }">{{ row.rejectCause || '—' }}</template>
        </el-table-column>
        <el-table-column label="图片" width="90">
          <template #default="{ row }">
            <el-image
              v-if="row.picUrl"
              :src="row.picUrl"
              :preview-src-list="[row.picUrl]"
              preview-teleported
              fit="cover"
              style="width: 56px; height: 56px"
            />
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!gatherCheckLoading && !gatherCheckRows.length" class="empty-note">
        没有查到采集审核记录：未挂 SKU 的商品要填厂商商品 id 直查。
      </div>
      <template #footer>
        <el-button @click="gatherCheckVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="consoleVisible"
      title="采集台（柜前拍摄一条龙）"
      width="880px"
      :close-on-click-modal="false"
      @close="onConsoleClose"
    >
      <el-alert
        class="dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="会话期间该柜自动进入采集模式（停止售货），会话结束自动恢复营业"
      >
        <template #default>
          链路：开门 → 建条目 → 开始拍摄 → 拍摄窗口 → 关门收尾 →
          等厂商上传。现场按页面提示动作即可；
          卡住用救援按钮（继续/放弃上传、清批次），或到设备详情「退出采集模式」兜底。
        </template>
      </el-alert>

      <template v-if="!consoleRun || isTerminal(consoleRun)">
        <el-form label-width="92px">
          <el-form-item label="柜机" required>
            <el-select
              v-model="consoleDeviceId"
              filterable
              placeholder="选择柜机"
              style="width: 100%"
            >
              <el-option
                v-for="c in cabinets"
                :key="c.deviceId"
                :label="`${c.deviceName || c.deviceId}（${c.identifier || '无编号'}）`"
                :value="c.deviceId"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="待采商品" required>
            <div class="console-pending-bar">
              <el-button
                size="small"
                :loading="consolePendingLoading"
                @click="loadConsolePending()"
              >
                拉取待采集列表
              </el-button>
              <span v-if="consolePendingLoading" class="form-hint">读取将邑待采集商品…</span>
              <span v-else-if="!consolePending.length" class="form-hint">
                将邑侧没有待采集商品（都采过了，或先在「将邑挂接」里新增）
              </span>
            </div>
            <el-table
              v-if="consolePending.length"
              :data="consolePending"
              size="small"
              border
              max-height="220"
              highlight-current-row
              class="console-pending-table"
              @current-change="(row: PendingProduct | null) => (consoleSelected = row)"
            >
              <el-table-column label="商品" min-width="220">
                <template #default="{ row }">{{ row.name }}</template>
              </el-table-column>
              <el-table-column prop="brandName" label="品牌" width="90" />
              <el-table-column prop="specs" label="规格" width="90" />
              <el-table-column label="图片" width="80">
                <template #default="{ row }">
                  <el-image
                    v-if="row.mainImg"
                    :src="row.mainImg"
                    fit="cover"
                    style="width: 44px; height: 44px"
                  />
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
            </el-table>
            <div v-if="consoleSelected" class="form-hint">
              已选：{{ consoleSelected.name }}（厂商商品 id {{ consoleSelected.productId }}）
            </div>
          </el-form-item>
          <el-form-item label="拍摄窗口">
            <el-input-number v-model="consoleShoot" :min="20" :max="120" :step="5" />
            <div class="form-hint">
              实测两摄合计约 25–28 张/秒，厂商门槛「每摄 ≥500 张」：45 秒稳（下摄慢，留余量）。
            </div>
          </el-form-item>
        </el-form>
      </template>

      <template v-else>
        <div class="console-phase">
          <el-tag size="large" :type="consolePhaseTagType(consoleRun.phase)">
            {{ consolePhaseText(consoleRun.phase) }}
          </el-tag>
          <span class="console-hint">{{ consoleHint(consoleRun) }}</span>
        </div>
        <pre class="console-log">{{ consoleLogText }}</pre>
        <el-table v-if="consoleRun.upload.length" :data="consoleRun.upload" size="small" border>
          <el-table-column prop="productInfo" label="条目" min-width="220" />
          <el-table-column label="上传" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="uploadTagType(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="厂商判词" min-width="200">
            <template #default="{ row }">
              {{ row.errorMessage || row.rejectReason || '—' }}
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button v-if="!consoleRun || isTerminal(consoleRun)" @click="consoleVisible = false">
          关闭
        </el-button>
        <el-button
          v-if="consoleRun && !isTerminal(consoleRun)"
          v-hasPermi="['ops:device:edit']"
          :disabled="consoleRescuing"
          @click="consoleAbort"
        >
          中止会话
        </el-button>
        <template v-if="consoleRun && (consoleRun.phase === 'UPLOADING' || isTerminal(consoleRun))">
          <el-button
            v-hasPermi="['ops:device:edit']"
            :loading="consoleRescuing"
            @click="consoleRescue('resume')"
          >
            继续上传
          </el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            :loading="consoleRescuing"
            @click="consoleRescue('forgo')"
          >
            放弃上传
          </el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            :loading="consoleRescuing"
            @click="consoleRescue('clear')"
          >
            清批次
          </el-button>
        </template>
        <el-button v-if="consoleRun && isTerminal(consoleRun)" @click="consoleReset">
          重新发起
        </el-button>
        <el-button
          v-if="!consoleRun || isTerminal(consoleRun)"
          v-hasPermi="['ops:device:edit']"
          type="primary"
          :loading="consoleStarting"
          :disabled="!consoleDeviceId || !consoleSelected"
          @click="startConsole"
        >
          开始采集
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import VisionFlowSteps from '@/components/vision/VisionFlowSteps.vue';

/**
 * 商品学习库（租户级）：将邑云端的模型与「可学习商品名单」是商户资产，一次学习全部柜机共用；
 * 但 classId 是 classes.txt 按识别名排序的行号（H-27），重训即整体平移 ⇒ 映射/库存/价格
 * 仍逐柜。本页只做总览与（可选）学习触发，采集编排走本页「开始采集」（§4.3.3–4.3.14）。
 *
 * 🔴 「可学习名单」（§4.4.2）只证明采集成档且厂商审核通过，<b>不证明已学成</b>——2026-10-27
 * 实测商品 41175 在名单里时，厂商模型数与学习中数同时为 0。本页唯一能证「进了识别链路」的是
 * 各柜的 ACTIVE 映射（「已上架柜机」列）。
 */

interface LearningModel {
  id: number;
  modelName: string | null;
  /** 厂商侧「模型初命名」：我方提交的 modelName 被厂商改写时，只有这列留着原名。 */
  customerModelName: string | null;
  quantity: number;
  industrialControlModel: string | null;
  modelTextUrl: string | null;
  modelUrl: string | null;
}

interface ProductCoverage {
  deviceId: string;
  deviceName: string | null;
  classId: number;
  mappingVerified: boolean;
}

/** §4.4.2 可学习名单的一行；🔴 在列 ≠ 已学成，能识别与否只看 coverage。 */
interface LearnableProductRow {
  jiangyiProductId: number;
  name: string | null;
  textName: string | null;
  barCode: string | null;
  skuId: string | null;
  skuName: string | null;
  coverage: ProductCoverage[];
}

interface LearnableSku {
  skuId: string;
  skuName: string | null;
  jiangyiProductId: string | null;
  jiangyiName: string | null;
  textName: string | null;
  syncStatus: string;
  /** 已在厂商可学习名单里——不表示已学成。 */
  inVendorList: boolean;
  /** 该识别名已在哪些柜上有 ACTIVE 挂牌，这才是「已进识别链路」。 */
  mappedDeviceIds: string[];
}

interface LearningCabinet {
  deviceId: string;
  deviceName: string | null;
  identifier: string | null;
  classesVersion: string | null;
  mappingVerified: boolean;
  activeMappingCount: number;
  gatherLocked: boolean;
  onlineStatus: string | null;
  lastWsOnlineAt: string | null;
}

interface TrainingAttempt {
  deviceId: string | null;
  deviceName: string | null;
  skuId: string | null;
  skuName: string | null;
  modelName: string | null;
  status: string;
  createdAt: string | null;
  finishedAt: string | null;
}

interface LearningLibraryView {
  models: LearningModel[];
  learnableProducts: LearnableProductRow[];
  learnableSkus: LearnableSku[];
  cabinets: LearningCabinet[];
  recentTrainings: TrainingAttempt[];
  vendorError: string | null;
}

/** 将邑 §4.4.5 原始回包行（厂商可能多给字段，只声明我们要展示的）。 */
interface StudyingProduct {
  productId?: number;
  name?: string | null;
  status?: string | null;
  rejectCause?: string | null;
  mainImg?: string | null;
}

/** 将邑 §4.4.6 审核项（后端 GatherCheckItem）。 */
interface GatherCheckItem {
  id: number;
  productId: number;
  status: string;
  rejectCause: string | null;
  name: string | null;
  picUrl: string | null;
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const library = ref<LearningLibraryView | null>(null);
const loading = ref(false);
const keyword = ref('');

// 上架顺带改价/铺货各走独立权限点（与单柜定价、补货页同一把钥匙）；无权时输入框禁用而非等 403。
const canEditPricing = computed(() => auth.hasPerm('ops:pricing:edit'));
const canEditReplenishment = computed(() => auth.hasPerm('ops:replenishment:edit'));

const cabinets = computed(() => library.value?.cabinets ?? []);
const learnableSkus = computed(() => library.value?.learnableSkus ?? []);
const learnableSkuCount = computed(() => learnableSkus.value.length);
const filteredProducts = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  const rows = library.value?.learnableProducts ?? [];
  if (!kw) return rows;
  return rows.filter((r) =>
    [r.name, r.textName, r.barCode, r.skuId, r.skuName]
      .filter((v): v is string => !!v)
      .some((v) => v.toLowerCase().includes(kw))
  );
});

// ---- 选项卡：四个表各自过滤 + 前端分页（数据量小，不引入后端分页）----
const activeTab = ref('products');
const cabinetKeyword = ref('');
const modelKeyword = ref('');
const trainingKeyword = ref('');
const productPage = ref(1);
const productSize = ref(10);
const cabinetPage = ref(1);
const cabinetSize = ref(10);
const modelPage = ref(1);
const modelSize = ref(10);
const trainingPage = ref(1);
const trainingSize = ref(10);

watch([keyword, cabinetKeyword, modelKeyword, trainingKeyword], () => {
  productPage.value = 1;
  cabinetPage.value = 1;
  modelPage.value = 1;
  trainingPage.value = 1;
});

const filteredCabinets = computed(() => {
  const kw = cabinetKeyword.value.trim().toLowerCase();
  const rows = library.value?.cabinets ?? [];
  if (!kw) return rows;
  return rows.filter((r) =>
    [r.deviceId, r.deviceName, r.identifier]
      .filter((v): v is string => !!v)
      .some((v) => v.toLowerCase().includes(kw))
  );
});

const filteredModels = computed(() => {
  const kw = modelKeyword.value.trim().toLowerCase();
  const rows = library.value?.models ?? [];
  if (!kw) return rows;
  return rows.filter(
    (r) =>
      (r.modelName ?? '').toLowerCase().includes(kw) ||
      (r.customerModelName ?? '').toLowerCase().includes(kw)
  );
});

const filteredTrainings = computed(() => {
  const kw = trainingKeyword.value.trim().toLowerCase();
  const rows = library.value?.recentTrainings ?? [];
  if (!kw) return rows;
  return rows.filter((r) =>
    [r.skuId, r.skuName, r.modelName, r.deviceId, r.deviceName]
      .filter((v): v is string => !!v)
      .some((v) => v.toLowerCase().includes(kw))
  );
});

const pagedProducts = computed(() =>
  filteredProducts.value.slice(
    (productPage.value - 1) * productSize.value,
    productPage.value * productSize.value
  )
);

const pagedCabinets = computed(() =>
  filteredCabinets.value.slice(
    (cabinetPage.value - 1) * cabinetSize.value,
    cabinetPage.value * cabinetSize.value
  )
);

const pagedModels = computed(() =>
  filteredModels.value.slice(
    (modelPage.value - 1) * modelSize.value,
    modelPage.value * modelSize.value
  )
);

const pagedTrainings = computed(() =>
  filteredTrainings.value.slice(
    (trainingPage.value - 1) * trainingSize.value,
    trainingPage.value * trainingSize.value
  )
);

const trainingVisible = ref(false);
const trainingSubmitting = ref(false);
const trainingForm = ref({ deviceId: '', skuIds: [] as string[], modelName: '' });

interface BatchTrainingResult {
  deviceId: string;
  modelName: string;
  count: number;
  items: { ticketId: number; skuId: string; jiangyiProductId: string; status: string }[];
}

interface ShelfDeviceResult {
  deviceId: string;
  deviceName: string | null;
  status: 'SHELVED' | 'ALREADY' | 'SKIPPED' | 'FAILED';
  classId: number | null;
  priceCents: number | null;
  quantity: number | null;
  message: string | null;
}

interface ShelfResult {
  jiangyiProductId: string;
  skuId: string;
  skuName: string | null;
  textName: string;
  requestedDevices: number;
  devices: ShelfDeviceResult[];
}

const shelfVisible = ref(false);
const shelfSubmitting = ref(false);
const shelfProduct = ref<LearnableProductRow | null>(null);
const shelfResult = ref<ShelfResult | null>(null);
const shelfForm = ref<{
  deviceIds: string[];
  priceYuan: number | undefined;
  quantity: number | undefined;
}>({ deviceIds: [], priceYuan: undefined, quantity: undefined });

// ---- 厂商侧只读查询（学习中商品 §4.4.5 / 采集审核 §4.4.6）----
const studyingVisible = ref(false);
const studyingLoading = ref(false);
const studyingRows = ref<StudyingProduct[]>([]);

const gatherCheckVisible = ref(false);
const gatherCheckLoading = ref(false);
const gatherCheckProductId = ref('');
const gatherCheckRows = ref<GatherCheckItem[]>([]);

async function loadStudying() {
  studyingLoading.value = true;
  try {
    const data = await api.request<StudyingProduct[]>(
      AdminEndpoints.jiangyiTrainingProducts,
      'GET'
    );
    studyingRows.value = Array.isArray(data) ? data : [];
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取学习中商品失败'));
  } finally {
    studyingLoading.value = false;
  }
}

function openStudying() {
  studyingVisible.value = true;
  void loadStudying();
}

async function queryGatherCheck() {
  gatherCheckLoading.value = true;
  try {
    const pid = gatherCheckProductId.value.trim();
    const qs = pid ? `?productId=${encodeURIComponent(pid)}` : '';
    gatherCheckRows.value = await api.request<GatherCheckItem[]>(
      AdminEndpoints.jiangyiGatherCheck + qs,
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取采集审核列表失败'));
  } finally {
    gatherCheckLoading.value = false;
  }
}

function openGatherCheck() {
  gatherCheckVisible.value = true;
  if (!gatherCheckRows.value.length) void queryGatherCheck();
}

function auditStatusText(status?: string | null) {
  if (status === 'pass') return '通过';
  if (status === 'reject') return '驳回';
  if (status === 'wait') return '待审核';
  return status || '—';
}

function auditStatusType(status?: string | null) {
  if (status === 'pass') return 'success';
  if (status === 'reject') return 'danger';
  return 'warning';
}

// ---- 采集台（§4.3.3–4.3.14 一条龙；后端内存态会话，重启即清）----

interface PendingProduct {
  productId: number;
  name: string;
  brandName: string | null;
  specs: string | null;
  category: string | null;
  mainImg: string | null;
  barCodeSource: string | null;
}

interface ConsoleLogLine {
  ts: string;
  level: string;
  message: string;
}

interface ConsoleUploadRow {
  id: number;
  productInfo: string;
  status: string;
  errorMessage: string;
  rejectReason: string;
  mainImg: string;
}

interface ConsoleRun {
  runId: string;
  deviceId: string;
  identifier: string;
  phase: string;
  shootSeconds: number;
  abortRequested: boolean;
  startedAt: string | null;
  finishedAt: string | null;
  failure: string | null;
  entry: Record<string, unknown>;
  logs: ConsoleLogLine[];
  upload: ConsoleUploadRow[];
}

const consoleVisible = ref(false);
const consoleDeviceId = ref('');
const consoleShoot = ref(45);
const consolePending = ref<PendingProduct[]>([]);
const consolePendingLoading = ref(false);
const consoleSelected = ref<PendingProduct | null>(null);
const consoleRun = ref<ConsoleRun | null>(null);
const consoleStarting = ref(false);
const consoleRescuing = ref(false);
let consoleTimer: ReturnType<typeof setInterval> | undefined;

function isTerminal(run: ConsoleRun) {
  return ['DONE', 'FAILED', 'ABORTED'].includes(run.phase);
}

const consoleLogText = computed(() =>
  (consoleRun.value?.logs ?? []).map((l) => `[${formatDateTime(l.ts)}] ${l.message}`).join('\n')
);

function openConsole() {
  consoleVisible.value = true;
  const fromQuery = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  if (!consoleDeviceId.value) {
    consoleDeviceId.value = cabinets.value.some((c) => c.deviceId === fromQuery)
      ? fromQuery
      : cabinets.value[0]?.deviceId || '';
  }
  void refreshConsoleState();
}

async function refreshConsoleState() {
  if (!consoleDeviceId.value) return;
  try {
    consoleRun.value = await api.request<ConsoleRun | null>(
      AdminEndpoints.jiangyiGatherConsoleState(consoleDeviceId.value),
      'GET'
    );
  } catch {
    consoleRun.value = null;
  }
  schedulePoll();
}

function schedulePoll() {
  stopPoll();
  if (consoleVisible.value && consoleRun.value && !isTerminal(consoleRun.value)) {
    consoleTimer = setInterval(() => {
      void pollConsole();
    }, 2000);
  }
}

function stopPoll() {
  if (consoleTimer) {
    clearInterval(consoleTimer);
    consoleTimer = undefined;
  }
}

async function pollConsole() {
  if (!consoleDeviceId.value) return;
  try {
    consoleRun.value = await api.request<ConsoleRun | null>(
      AdminEndpoints.jiangyiGatherConsoleState(consoleDeviceId.value),
      'GET'
    );
    if (!consoleRun.value || isTerminal(consoleRun.value)) stopPoll();
  } catch {
    /* 单次轮询失败忽略，下个周期再试 */
  }
}

async function loadConsolePending() {
  consolePendingLoading.value = true;
  try {
    consolePending.value = await api.request<PendingProduct[]>(
      AdminEndpoints.jiangyiPendingGather(),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '读取待采集商品失败'));
  } finally {
    consolePendingLoading.value = false;
  }
}

async function startConsole() {
  const entry = consoleSelected.value;
  if (!consoleDeviceId.value || !entry) {
    ElMessage.warning('请选择柜机和待采集商品');
    return;
  }
  consoleStarting.value = true;
  try {
    consoleRun.value = await api.request<ConsoleRun>(
      AdminEndpoints.jiangyiGatherConsoleStart,
      'POST',
      {
        deviceId: consoleDeviceId.value,
        productId: entry.productId,
        name: entry.name,
        brand: entry.brandName ?? '',
        specs: entry.specs ?? '',
        category: entry.category ?? '',
        barCodeSource: entry.barCodeSource ?? '',
        shootSeconds: consoleShoot.value
      }
    );
    ElMessage.success('会话已启动：按页面提示开门、摆商品');
    schedulePoll();
  } catch (e) {
    ElMessage.error(errorMessage(e, '发起采集失败'));
  } finally {
    consoleStarting.value = false;
  }
}

async function consoleAbort() {
  consoleRescuing.value = true;
  try {
    consoleRun.value = await api.request<ConsoleRun>(
      AdminEndpoints.jiangyiGatherConsoleAbort,
      'POST',
      { deviceId: consoleDeviceId.value }
    );
    schedulePoll();
  } catch (e) {
    ElMessage.error(errorMessage(e, '中止失败'));
  } finally {
    consoleRescuing.value = false;
  }
}

async function consoleRescue(action: 'resume' | 'forgo' | 'clear') {
  const urls = {
    resume: AdminEndpoints.jiangyiGatherConsoleResumeUpload,
    forgo: AdminEndpoints.jiangyiGatherConsoleForgoUpload,
    clear: AdminEndpoints.jiangyiGatherConsoleClearBatch
  } as const;
  if (action === 'forgo') {
    try {
      await ElMessageBox.confirm('放弃上传＝整批丢弃，厂商侧不可逆。确定放弃？', '放弃上传', {
        type: 'warning',
        confirmButtonText: '放弃',
        cancelButtonText: '取消'
      });
    } catch {
      return;
    }
  }
  consoleRescuing.value = true;
  try {
    consoleRun.value = await api.request<ConsoleRun>(urls[action], 'POST', {
      deviceId: consoleDeviceId.value
    });
    schedulePoll();
  } catch (e) {
    ElMessage.error(errorMessage(e, '救援动作失败'));
  } finally {
    consoleRescuing.value = false;
  }
}

function consoleReset() {
  consoleRun.value = null;
  consoleSelected.value = null;
}

function onConsoleClose() {
  stopPoll();
  void load();
}

function consolePhaseText(phase: string) {
  const map: Record<string, string> = {
    WAIT_DOOR: '等开门',
    PREPARING: '建条目/等就绪',
    SHOOTING: '拍摄中',
    WRAPPING: '关门收尾',
    UPLOADING: '等厂商上传',
    DONE: '完成',
    FAILED: '失败',
    ABORTED: '已中止'
  };
  return map[phase] ?? phase;
}

function consolePhaseTagType(phase: string) {
  if (phase === 'DONE') return 'success';
  if (phase === 'FAILED') return 'danger';
  if (phase === 'ABORTED') return 'info';
  if (phase === 'SHOOTING') return 'warning';
  return 'primary';
}

function consoleHint(run: ConsoleRun) {
  const hints: Record<string, string> = {
    WAIT_DOOR: '请到柜机前把门拉开（60 秒内），门一开自动继续',
    PREPARING: '门已开：条目建立中，把商品拿在手上准备',
    SHOOTING: '把商品摆到摄像头前，在柜内缓慢上下扫动，保持到阶段结束',
    WRAPPING: '可以关门了，系统自动收尾',
    UPLOADING: '已收尾，等厂商侧上传完成（不出结果别清批次）',
    DONE: '会话完成；要卖这批商品先等厂商审核通过，再触发学习',
    FAILED: `会话失败：${run.failure ?? '见日志'}`,
    ABORTED: '会话已中止'
  };
  return hints[run.phase] ?? '';
}

function uploadTagType(status: string) {
  if (status === 'success') return 'success';
  if (status === 'fail') return 'danger';
  return 'warning';
}

onUnmounted(() => {
  stopPoll();
});

async function load() {
  loading.value = true;
  try {
    library.value = await api.request<LearningLibraryView>(
      AdminEndpoints.jiangyiLearningLibrary,
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载商品学习库失败'));
  } finally {
    loading.value = false;
  }
}

function openTraining() {
  const fromQuery = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  const fallback = cabinets.value[0]?.deviceId || '';
  trainingForm.value = {
    deviceId: cabinets.value.some((c) => c.deviceId === fromQuery) ? fromQuery : fallback,
    skuIds: [],
    modelName: library.value?.models[0]?.modelName || ''
  };
  trainingVisible.value = true;
}

async function submitTraining() {
  const { deviceId, modelName } = trainingForm.value;
  const skuIds = trainingForm.value.skuIds.filter((id) => !!id);
  if (!deviceId) {
    ElMessage.warning('请选择柜机');
    return;
  }
  if (!skuIds.length) {
    ElMessage.warning('请至少勾选一个商品');
    return;
  }
  if (!modelName.trim()) {
    ElMessage.warning('请选择或填写模型名');
    return;
  }
  trainingSubmitting.value = true;
  try {
    const result = await api.request<BatchTrainingResult>(
      AdminEndpoints.jiangyiGatherTrainingBatch,
      'POST',
      { deviceId, skuIds, modelName: modelName.trim() }
    );
    ElMessage.success(
      `已提交 ${result.count} 个商品的学习（一次厂商调用）；回调成功后会记录为完成，若该柜在采集模式中会自动退出`
    );
    trainingVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '触发学习失败'));
  } finally {
    trainingSubmitting.value = false;
  }
}

function goMappings(deviceId?: string) {
  void router.push({ path: '/vision-mappings', query: deviceId ? { deviceId } : {} });
}

function openShelf(row: LearnableProductRow) {
  shelfProduct.value = row;
  shelfResult.value = null;
  shelfForm.value = { deviceIds: [], priceYuan: undefined, quantity: undefined };
  shelfVisible.value = true;
}

async function submitShelf() {
  const product = shelfProduct.value;
  if (!product) return;
  const deviceIds = shelfForm.value.deviceIds.filter((id) => !!id);
  if (!deviceIds.length) {
    ElMessage.warning('请至少选择一台柜机');
    return;
  }
  const priceCents =
    typeof shelfForm.value.priceYuan === 'number'
      ? Math.round(shelfForm.value.priceYuan * 100)
      : null;
  const quantity =
    typeof shelfForm.value.quantity === 'number' ? Math.round(shelfForm.value.quantity) : null;
  shelfSubmitting.value = true;
  try {
    shelfResult.value = await api.request<ShelfResult>(
      AdminEndpoints.jiangyiLearningLibraryShelf,
      'POST',
      { jiangyiProductId: String(product.jiangyiProductId), deviceIds, priceCents, quantity }
    );
    const done = shelfResult.value.devices.filter(
      (d) => d.status === 'SHELVED' || d.status === 'ALREADY'
    ).length;
    ElMessage.success(
      `${done} / ${shelfResult.value.devices.length} 台柜机已上架，其余跳过/失败原因见下`
    );
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '跨柜上架失败'));
  } finally {
    shelfSubmitting.value = false;
  }
}

function closeShelf() {
  shelfVisible.value = false;
  shelfResult.value = null;
}

function shelfStatusText(status: ShelfDeviceResult['status']) {
  if (status === 'SHELVED') return '已上架';
  if (status === 'ALREADY') return '已在售';
  if (status === 'SKIPPED') return '跳过';
  return '失败';
}

function shelfStatusType(status: ShelfDeviceResult['status']) {
  if (status === 'SHELVED') return 'success';
  if (status === 'ALREADY') return 'info';
  if (status === 'SKIPPED') return 'warning';
  return 'danger';
}

function goDevice(deviceId: string) {
  void router.push({ path: `/devices/${encodeURIComponent(deviceId)}` });
}

function trainingStatusText(status: string) {
  if (status === 'FINISHED') return '已完成';
  if (status === 'FAILED') return '失败';
  return '等待回调';
}

function trainingStatusType(status: string) {
  if (status === 'FINISHED') return 'success';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

function formatInstant(t: string | null) {
  return t ? formatDateTime(t) : '—';
}

onMounted(() => {
  void load();
  const dialog = typeof route.query.dialog === 'string' ? route.query.dialog : '';
  if (dialog === 'console') openConsole();
  else if (dialog === 'audit') openGatherCheck();
  else if (dialog === 'training') openTraining();
});

let activatedOnce = false;
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true;
    return;
  }
  void load();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-card-head__title .title {
  font-size: 16px;
  font-weight: 600;
}
.page-card-head__title .hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.vendor-alert {
  margin-top: 8px;
}
.stat-line {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  justify-content: center;
  margin: 12px 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.stat-line b {
  color: var(--el-text-color-primary);
}
.section-title {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.library-tabs {
  margin-top: 8px;
}
.table-pagination {
  margin-top: 10px;
  justify-content: flex-end;
}
.filter-bar {
  margin-bottom: 8px;
}
.coverage-tag {
  margin: 0 6px 4px 0;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.sub {
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-placeholder);
}
.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.empty-note {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.dialog-alert {
  margin-bottom: 12px;
}
.dialog-toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}
.console-pending-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.console-pending-table {
  width: 100%;
  margin-top: 6px;
}
.console-phase {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.console-hint {
  font-size: 13px;
  color: var(--el-text-color-primary);
}
.console-log {
  margin: 0 0 10px;
  padding: 10px;
  max-height: 260px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
}
.form-hint {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
