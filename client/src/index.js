import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import App from './app/App';
import * as theme from './app/service/theme.js';

const root = ReactDOM.createRoot(document.getElementById('root'));
root.render(
    <App/>
);

window.onload = async () => {
    await theme.initCssVars();
}
