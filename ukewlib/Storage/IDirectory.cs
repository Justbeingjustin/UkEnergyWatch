using System;
using System.Collections.Generic;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace Ukew
{
    public interface IDirectory
    {
        Task<IEnumerable<Ukew.Storage.FileInfo>> ListFilesAsync(CancellationToken ct = default(CancellationToken));
        Task AppendAsync(string fileId, IEnumerable<byte> bytes, CancellationToken ct = default(CancellationToken));
        Task<Stream> ReadAsync(string fileId, CancellationToken ct = default(CancellationToken));
        IDisposable RegisterOnChange(Action fn);
    }
}