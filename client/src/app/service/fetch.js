import * as spaceAuth from "./spaceAuth";

export default async function fetchFromServer(path, method) {
    const httpMethod = method === undefined ? 'GET' : method;
    return await fetch(path, {method: httpMethod, headers: {"Authorization": "Bearer " + spaceAuth.getUserToken()}});
}
