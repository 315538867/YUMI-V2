export const EMPLOYEE_COLOR_COUNT = 5

export function employeeColorIndex(employeeId = '', explicit = -1): number {
  if (explicit >= 0) {
    return explicit % EMPLOYEE_COLOR_COUNT
  }
  if (employeeId === '') {
    return 0
  }
  let hash = 0
  for (const char of employeeId) {
    hash = (hash * 31 + (char.codePointAt(0) ?? 0)) % 997
  }
  return hash % EMPLOYEE_COLOR_COUNT
}
