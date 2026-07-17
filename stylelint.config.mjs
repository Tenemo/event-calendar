/** @type {import("stylelint").Config} */
export default {
  extends: ["stylelint-config-standard"],
  reportDescriptionlessDisables: true,
  reportInvalidScopeDisables: true,
  reportNeedlessDisables: true,
  rules: {
    // PrimeFaces overrides intentionally follow lower-specificity application rules.
    "no-descending-specificity": null,
  },
};
