export type TransportMode = 'real' | 'mock'
export const mode: TransportMode = new URLSearchParams(window.location.search).get('transport') === 'mock' ? 'mock' : 'real'
export const pageUrl = (page: 'admin' | 'stage', selected = mode) => `/${page}?transport=${selected}`
