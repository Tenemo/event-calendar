import { Buffer } from "node:buffer";

export const DEFAULT_MAXIMUM_STANDARD_INPUT_BYTES = 64 * 1024 * 1024;

export async function readBoundedUtf8Input(
    inputStream,
    maximumInputBytes = DEFAULT_MAXIMUM_STANDARD_INPUT_BYTES,
) {
    if (!Number.isSafeInteger(maximumInputBytes) || maximumInputBytes <= 0) {
        throw new Error("The standard-input byte limit must be a positive safe integer.");
    }

    const inputChunks = [];
    let totalInputBytes = 0;
    for await (const rawInputChunk of inputStream) {
        const inputChunk = Buffer.isBuffer(rawInputChunk)
            ? rawInputChunk
            : Buffer.from(rawInputChunk);
        if (inputChunk.length > maximumInputBytes - totalInputBytes) {
            throw new Error(
                `Standard input exceeded the ${maximumInputBytes}-byte safety limit.`,
            );
        }
        inputChunks.push(inputChunk);
        totalInputBytes += inputChunk.length;
    }

    return Buffer.concat(inputChunks, totalInputBytes).toString("utf8");
}
