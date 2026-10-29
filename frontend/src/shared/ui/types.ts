export interface TopNavItem {
  key: string
  label: string
  path?: string
}

export interface SelectOption {
  label: string
  value: string
}

export interface TableColumn {
  key: string
  label: string
  width?: number | string
  minWidth?: number | string
  align?: 'left' | 'center' | 'right'
}

export type TaskStatus = 'pending' | 'done' | 'arranged'

export interface DetailFact {
  label: string
  value: string
  hint?: string
}

export interface SectionTab {
  key: string
  label: string
  count?: number | null
}

export interface TimelineItem {
  title: string
  description?: string
  time?: string
  tone?: 'done' | 'current' | 'neutral'
}

export interface SourceNode {
  label: string
  value: string
  hint?: string
}
