'use strict';
exports.handler = async (event) => {
    const method = event.httpMethod;
    if (method === 'OPTIONS') {
        return {
            // server must reply with 200 for CORS preflight request to succeed
            statusCode: 200,
            headers: {
                'Access-Control-Allow-Origin': '*',
                'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type, Authorization',
                'Access-Control-Allow-Credentials': 'true',
            },
            body: ''
        };
    }

    const path = event.path;
    if (path === '/health') {
        return {
            statusCode: 200,
            body: 'OK'
        };
    }

    return {
        statusCode: 418,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, PUT, PATCH, DELETE, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type',
            'Content-Type': 'application/json'
        },
        body: '{"error":{"code":418,"message":"funkybit is currently undergoing maintenance, we\'ll be back soon."}}'
    };
};