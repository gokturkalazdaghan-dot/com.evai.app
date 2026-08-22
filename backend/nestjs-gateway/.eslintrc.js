// backend/nestjs-gateway/.eslintrc.js
//
// NEDEN VAR
// ---------
// package.json'da "lint" betigi ve CI'da `npm run lint` adimi vardi ama
// ESLint yapilandirmasi HIC olmamisti. Sonuc: gateway CI'i olusturuldugu
// gunden beri bu adimda kaliyordu. Kirmizi bir CI, korumasiz bir CI'dan
// daha kotudur -- koruma sagliyormus izlenimi verir, kimse bakmaz olur.
//
// ESLint 8 kullaniliyor, dolayisiyla eski (.eslintrc) bicim gecerli.
module.exports = {
  parser: '@typescript-eslint/parser',
  parserOptions: {
    project: 'tsconfig.json',
    tsconfigRootDir: __dirname,
    sourceType: 'module',
  },
  plugins: ['@typescript-eslint'],
  extends: [
    'plugin:@typescript-eslint/recommended',
  ],
  root: true,
  env: {
    node: true,
    jest: true,
  },
  ignorePatterns: ['.eslintrc.js', 'dist', 'node_modules', 'coverage'],
  rules: {
    // NestJS'te denetleyici ve servis metotlarinin donus tipleri cogu
    // zaman DTO'dan cikarilabiliyor; hepsini elle yazmak gurultu.
    '@typescript-eslint/explicit-function-return-type': 'off',
    '@typescript-eslint/explicit-module-boundary-types': 'off',

    // `any` YASAK DEGIL ama uyari: ucuncu parti SDK'larin tipsiz
    // yerleriyle calisirken kacinilmaz oluyor, yine de goze batmali.
    '@typescript-eslint/no-explicit-any': 'warn',

    // Kullanilmayan degisken gercek bir hata isareti; ancak alt cizgi
    // ile baslayanlar "bilerek kullanilmadi" demektir.
    '@typescript-eslint/no-unused-vars': [
      'error',
      { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
    ],
  },
};
