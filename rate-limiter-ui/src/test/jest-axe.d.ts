declare module "jest-axe" {
  export function axe(html: Element | string): Promise<{
    violations: readonly unknown[];
  }>;
}
