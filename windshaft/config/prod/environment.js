module.exports.credentials_encryption_key= process.env.ENCRYPTION_KEY;
module.exports.redis = {
    host: 'flow-maps-redis-master',
    max: 10
};
module.exports.renderer = {
    mapnik: {
        metatile: 4,
        bufferSize: 0 // no need for a buffer as it is just useful if we have labels/tags in the map.
    }
};
module.exports.enable_cors = false;
module.exports.statsd = {
    host: 'localhost',
    port: 9125,
    cacheDns: true
};