let spaceOrgInput = document.getElementById('space-org-input')
let installToSpaceLink = document.getElementById('install-to-space-link')

spaceOrgInput.addEventListener('input', (event) => {
    if (event.target.value.trim() === '') {
        installToSpaceLink.classList.add('empty')
    } else {
        installToSpaceLink.classList.remove('empty')

        let spaceUrl = event.target.value
        if (!spaceUrl.startsWith("http"))
            spaceUrl = `https://${spaceUrl}`;

        installToSpaceLink.href = window.spaceAppInstallUrl.replace('https://space-org-hostname', spaceUrl)
    }
})
