const path = require('path');
const webpack = require('webpack');

module.exports = {
    entry: './src/main/resources/org/ngengine/platform/teavm/TeaVMBinds.js',
    output: {
        filename: 'TeaVMBinds.bundle.js',
        path: path.resolve(__dirname, 'build/webpack-output/org/ngengine/platform/teavm'),
        library: {
            type: 'module'
        }
    },
    optimization: {
        minimize: false,
        mangleExports: false,
        concatenateModules: false
    },
    experiments: {
        outputModule: true
    },
    mode: 'production',
    module: {
        rules: [
            {
                test: /\.js$/,
                exclude: /node_modules/,
                use: {
                    loader: 'babel-loader',
                    options: {
                        presets: ['@babel/preset-env']
                    }
                }
            }
        ]
    },
    resolve: {
        fallback: {
            "buffer": require.resolve("buffer/"),
            "path": require.resolve("path-browserify"),
            "crypto": require.resolve("crypto-browserify")
        }
    },
    plugins: [
        new webpack.ProvidePlugin({
            Buffer: ['buffer', 'Buffer']
        })
    ]
};