export let isDark = null;
export let cssVars = {};

export async function initCssVars() {
    // subscribe to the changes in theme css variables
    window.addEventListener("message", (e) => {
        if (e.data.properties !== undefined) {
            applyCssVars(e.data);
        }
    });

    const themeCssVars = await getCssVarsAndSubscribeForChanges();
    applyCssVars(themeCssVars);
}

function applyCssVars(themeProperties) {
    isDark = themeProperties.isDark;
    let newCssVars = {};
    themeProperties.properties.forEach((cssVar, i) => {
        document.documentElement.style.setProperty(cssVar.name, cssVar.value);
        newCssVars[cssVar.name] = cssVar.value;
    })
    cssVars = newCssVars;
}

function getCssVarsAndSubscribeForChanges() {
    return new Promise((resolve) => {
        const channel = new MessageChannel();
        channel.port1.onmessage = e => resolve(e.data);
        window.parent.postMessage({type: "GetThemePropertiesRequest", subscribeForUpdates: true}, "*", [channel.port2]);
    });
}
