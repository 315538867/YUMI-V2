import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  EntryConfiguration,
  ProductDefinition,
  ProductStatus,
  ProductView,
  SchemeInput,
  SchemeView,
} from '@/modules/product/contracts'

function query(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && String(value).trim() !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text === '' ? '' : `?${text}`
}

export const productService = {
  list(keyword: string, status: ProductStatus | '', page = 1, size = 20): Promise<ProductView[]> {
    return api.query<ProductView[]>(`/api/products${query({ keyword, status, page, size })}`)
  },

  get(productBusinessId: string): Promise<ProductView> {
    return api.query<ProductView>(`/api/products/${encodeURIComponent(productBusinessId)}`)
  },

  create(definition: ProductDefinition): Promise<ProductView> {
    return api.command<ProductView, ProductDefinition>('/api/products', {
      idempotencyKey: newIdempotencyKey('product-create'),
      body: definition,
    })
  },

  update(
    productBusinessId: string,
    definition: ProductDefinition,
    expectedRowVersion: number,
  ): Promise<ProductView> {
    return api.command<ProductView, { definition: ProductDefinition; expectedRowVersion: number }>(
      `/api/products/${encodeURIComponent(productBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('product-update'),
        body: { definition, expectedRowVersion },
      },
    )
  },

  changeStatus(
    productBusinessId: string,
    targetStatus: ProductStatus,
    expectedRowVersion: number,
  ): Promise<ProductView> {
    return api.command<ProductView, { targetStatus: ProductStatus; expectedRowVersion: number }>(
      `/api/products/${encodeURIComponent(productBusinessId)}/status`,
      {
        idempotencyKey: newIdempotencyKey('product-status'),
        body: { targetStatus, expectedRowVersion },
      },
    )
  },

  orderEntry(schemeBusinessId: string): Promise<{
    ref: {
      snapshotType: string
      snapshotBusinessId: string
      ownerBusinessId: string
      snapshotVersion: number
      sourceContentVersion: number
      contentFingerprint: string
    }
    view: unknown
  }> {
    return api.query(`/api/products/schemes/${encodeURIComponent(schemeBusinessId)}/order-entry`)
  },

  schemes(productBusinessId: string): Promise<SchemeView[]> {
    return api.query<SchemeView[]>(`/api/products/${encodeURIComponent(productBusinessId)}/schemes`)
  },

  createScheme(
    productBusinessId: string,
    input: SchemeInput,
    expectedProductContentVersion: number,
  ): Promise<SchemeView> {
    return api.command<SchemeView, SchemeInput & { expectedProductContentVersion: number }>(
      `/api/products/${encodeURIComponent(productBusinessId)}/schemes`,
      {
        idempotencyKey: newIdempotencyKey('scheme-create'),
        body: { ...input, expectedProductContentVersion },
      },
    )
  },

  updateScheme(
    schemeBusinessId: string,
    input: SchemeInput,
    expectedProductContentVersion: number,
    expectedSchemeRowVersion: number,
  ): Promise<void> {
    return api.command<
      void,
      SchemeInput & {
        expectedProductContentVersion: number
        expectedSchemeRowVersion: number
      }
    >(`/api/products/schemes/${encodeURIComponent(schemeBusinessId)}`, {
      method: 'PUT',
      idempotencyKey: newIdempotencyKey('scheme-update'),
      body: { ...input, expectedProductContentVersion, expectedSchemeRowVersion },
    })
  },

  deactivateScheme(schemeBusinessId: string, expectedRowVersion: number): Promise<void> {
    return api.command<void, { expectedRowVersion: number }>(
      `/api/products/schemes/${encodeURIComponent(schemeBusinessId)}/deactivate`,
      {
        idempotencyKey: newIdempotencyKey('scheme-deactivate'),
        body: { expectedRowVersion },
      },
    )
  },

  entryConfiguration(): Promise<EntryConfiguration> {
    return api.query<EntryConfiguration>('/api/product-entry-configuration')
  },

  saveEntryDefaults(input: {
    glueLossRate: string
    glueUnitPrice: { amount: string; currency: string }
    colorPasteUnitPrice: { amount: string; currency: string }
    expectedRowVersion: number
  }): Promise<EntryConfiguration> {
    return api.command<EntryConfiguration, typeof input>(
      '/api/product-entry-configuration/defaults',
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('entry-defaults'),
        body: input,
      },
    )
  },

  saveMakingStar(
    starCode: string,
    input: {
      name: string
      standardMinutes: number
      sortOrder: number
      active: boolean
      expectedRowVersion: number
    },
  ): Promise<EntryConfiguration> {
    return api.command<EntryConfiguration, typeof input>(
      `/api/product-entry-configuration/making-stars/${encodeURIComponent(starCode)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('making-star'),
        body: input,
      },
    )
  },

  saveProcessTimeOption(
    optionBusinessId: string,
    input: {
      processCode: string
      optionCode: string
      name: string
      standardMinutes: number
      sortOrder: number
      active: boolean
      expectedRowVersion: number
    },
  ): Promise<EntryConfiguration> {
    return api.command<EntryConfiguration, typeof input>(
      `/api/product-entry-configuration/process-time-options/${encodeURIComponent(optionBusinessId)}`,
      {
        method: 'PUT',
        idempotencyKey: newIdempotencyKey('process-time-option'),
        body: input,
      },
    )
  },
}
