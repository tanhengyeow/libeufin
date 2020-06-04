/* eslint-disable @typescript-eslint/no-var-requires */
const CracoLessPlugin = require('craco-less');

module.exports = {
  plugins: [
    {
      plugin: CracoLessPlugin,
      options: {
        lessLoaderOptions: {
          modifyVars: {
            '@font-family': 'Open Sans',
            '@body-background': '#f0f2f5',
            '@menu-item-padding': '0 120px',
            '@menu-bg': 'none',
          },
          javascriptEnabled: true,
        },
      },
    },
  ],
};
